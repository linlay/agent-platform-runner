package com.linlay.agentplatform.voice.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.VoiceTtsProperties;
import com.linlay.agentplatform.config.VoiceWsProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class VoiceWebSocketHandler implements WebSocketHandler {

    private static final String PROVIDER_ERROR_CODE = "TTS_PROVIDER_ERROR";

    private final ObjectMapper objectMapper;
    private final VoiceWsProperties voiceWsProperties;
    private final VoiceTtsProperties voiceTtsProperties;
    private final VoiceWsAuthenticationService authenticationService;
    private final VoicePcmSynthesizer voicePcmSynthesizer;

    public VoiceWebSocketHandler(
            ObjectMapper objectMapper,
            VoiceWsProperties voiceWsProperties,
            VoiceTtsProperties voiceTtsProperties,
            VoiceWsAuthenticationService authenticationService,
            VoicePcmSynthesizer voicePcmSynthesizer
    ) {
        this.objectMapper = objectMapper;
        this.voiceWsProperties = voiceWsProperties;
        this.voiceTtsProperties = voiceTtsProperties;
        this.authenticationService = authenticationService;
        this.voicePcmSynthesizer = voicePcmSynthesizer;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        VoiceWsAuthenticationService.AuthResult authResult = authenticationService.authenticate(session);
        if (!authResult.authenticated()) {
            return rejectUnauthorized(session, authResult);
        }

        SessionState state = new SessionState();
        Sinks.Many<OutboundFrame> outbound = Sinks.many().unicast().onBackpressureBuffer();

        Mono<Void> receiveLoop = session.receive()
                .concatMap(message -> handleInboundMessage(message, state, outbound))
                .onErrorResume(ex -> emitError(outbound, "BAD_REQUEST", "invalid websocket frame payload", null))
                .doFinally(signalType -> {
                    closeActiveIfPresent(state);
                    outbound.tryEmitComplete();
                })
                .then();

        Mono<Void> sendLoop = session.send(outbound.asFlux().map(frame -> toWebSocketMessage(session, frame)));
        Mono<Void> runLoop = Mono.whenDelayError(receiveLoop, sendLoop);
        Mono<Void> timeoutLoop = Mono.delay(Duration.ofSeconds(voiceWsProperties.safeMaxSessionSeconds()))
                .flatMap(ignored -> emitError(outbound, "INVALID_STATE", "voice session timeout", null)
                        .then(session.close(CloseStatus.POLICY_VIOLATION)))
                .onErrorResume(ex -> Mono.empty());

        return Mono.firstWithSignal(runLoop, timeoutLoop)
                .doFinally(signalType -> {
                    closeActiveIfPresent(state);
                    outbound.tryEmitComplete();
                });
    }

    private Mono<Void> rejectUnauthorized(WebSocketSession session, VoiceWsAuthenticationService.AuthResult authResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("code", StringUtils.hasText(authResult.code()) ? authResult.code() : "UNAUTHORIZED");
        payload.put("message", StringUtils.hasText(authResult.message()) ? authResult.message() : "unauthorized");
        payload.put("timestamp", System.currentTimeMillis());
        String json = toJson(payload);
        return session.send(Mono.just(session.textMessage(json)))
                .then(session.close(CloseStatus.POLICY_VIOLATION));
    }

    private Mono<Void> handleInboundMessage(
            WebSocketMessage message,
            SessionState state,
            Sinks.Many<OutboundFrame> outbound
    ) {
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            return emitError(outbound, "BAD_REQUEST", "only text websocket frames are supported", null);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayloadAsText());
        } catch (Exception ex) {
            return emitError(outbound, "BAD_REQUEST", "invalid json payload", null);
        }

        String type = textValue(root.get("type"));
        if (!StringUtils.hasText(type)) {
            return emitError(outbound, "BAD_REQUEST", "type is required", null);
        }

        return switch (type) {
            case "tts.start" -> handleTtsStart(root, state, outbound);
            case "tts.chunk" -> handleTtsChunk(root, state, outbound);
            case "tts.commit" -> handleTtsCommit(root, state, outbound);
            case "tts.stop" -> handleTtsStop(root, state, outbound);
            case "asr.start", "asr.chunk", "asr.commit", "asr.stop" -> handleAsrPlaceholder(type, root, state, outbound);
            default -> emitError(outbound, "BAD_REQUEST", "unsupported command type: " + type, null);
        };
    }

    private Mono<Void> handleTtsStart(JsonNode root, SessionState state, Sinks.Many<OutboundFrame> outbound) {
        String requestId = defaultedRequestId(root.get("requestId"));
        String chatId = textValue(root.get("chatId"));
        String codec = normalizeCodec(root.get("codec"));
        String voice = textValue(root.get("voice"));
        int sampleRate = intValue(root.get("sampleRate"), voiceWsProperties.safeDefaultSampleRate());
        int channels = intValue(root.get("channels"), voiceWsProperties.safeDefaultChannels());
        VoicePcmSynthesizer.SessionOptions sessionOptions = new VoicePcmSynthesizer.SessionOptions(
                textValue(root.get("languageType")),
                textValue(root.get("mode")),
                textValue(root.get("format")),
                boxedIntValue(root.get("ttsSampleRate")),
                boxedFloatValue(root.get("speechRate")),
                boxedIntValue(root.get("volume")),
                boxedFloatValue(root.get("pitchRate")),
                boxedIntValue(root.get("bitRate")),
                textValue(root.get("instructions")),
                boxedBooleanValue(root.get("optimizeInstructions")),
                textValue(root.get("responseFormat"))
        );

        if (!"pcm".equals(codec)) {
            return emitError(outbound, "UNSUPPORTED_CODEC", "only pcm codec is supported in current release", requestId);
        }
        if (channels <= 0) {
            return emitError(outbound, "BAD_REQUEST", "channels must be positive", requestId);
        }
        if (sampleRate <= 0) {
            return emitError(outbound, "BAD_REQUEST", "sampleRate must be positive", requestId);
        }
        if (!voiceWsProperties.declaresCodec(codec)) {
            return emitError(outbound, "UNSUPPORTED_CODEC", "codec not enabled by server configuration", requestId);
        }
        if (!isValidSpeechRate(sessionOptions.speechRate())) {
            return emitError(outbound, "BAD_REQUEST", "speechRate must be in [0.5, 2.0]", requestId);
        }
        if (!isValidPitchRate(sessionOptions.pitchRate())) {
            return emitError(outbound, "BAD_REQUEST", "pitchRate must be in [0.5, 2.0]", requestId);
        }
        if (!isValidVolume(sessionOptions.volume())) {
            return emitError(outbound, "BAD_REQUEST", "volume must be in [0, 100]", requestId);
        }
        if (!isValidBitRate(sessionOptions.bitRate())) {
            return emitError(outbound, "BAD_REQUEST", "bitRate must be in [6, 510]", requestId);
        }
        if (!isValidTtsSampleRate(sessionOptions.sampleRate())) {
            return emitError(outbound, "BAD_REQUEST", "ttsSampleRate must be one of [8000,16000,24000,48000]", requestId);
        }

        interruptActiveTtsIfPresent(state, outbound, "tts.start");

        final VoicePcmSynthesizer.TtsSession ttsSession;
        try {
            ttsSession = voicePcmSynthesizer.startSession(
                    new VoicePcmSynthesizer.StartRequest(requestId, chatId, sampleRate, channels, voice, sessionOptions));
        } catch (Exception ex) {
            return emitError(outbound, PROVIDER_ERROR_CODE, providerErrorMessage(ex), requestId);
        }

        Sinks.One<Void> completionSignal = Sinks.one();
        Disposable audioSubscription = ttsSession.audioFlux().subscribe(
                frame -> outbound.tryEmitNext(new BinaryFrame(frame)),
                ex -> {
                    completionSignal.tryEmitError(ex);
                    if (isCurrentActive(state, requestId)) {
                        emitError(outbound, PROVIDER_ERROR_CODE, providerErrorMessage(ex), requestId).subscribe();
                        cleanupActiveTts(state, state.activeTts);
                    }
                },
                () -> completionSignal.tryEmitEmpty()
        );

        state.activeTts = new ActiveTts(
                requestId,
                chatId,
                codec,
                voice,
                sampleRate,
                channels,
                ttsSession,
                audioSubscription,
                completionSignal.asMono()
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tts.started");
        payload.put("requestId", requestId);
        payload.put("chatId", chatId);
        payload.put("codec", codec);
        payload.put("voice", voice);
        payload.put("sampleRate", sampleRate);
        payload.put("channels", channels);
        payload.put("timestamp", System.currentTimeMillis());
        emitText(outbound, payload);
        return Mono.empty();
    }

    private Mono<Void> handleTtsChunk(JsonNode root, SessionState state, Sinks.Many<OutboundFrame> outbound) {
        ActiveTts activeTts = state.activeTts;
        if (activeTts == null) {
            return emitError(outbound, "INVALID_STATE", "tts.start is required before tts.chunk", null);
        }

        String requestId = textValue(root.get("requestId"));
        if (StringUtils.hasText(requestId) && !activeTts.requestId.equals(requestId.trim())) {
            return emitError(outbound, "INVALID_STATE", "requestId does not match current tts session", requestId);
        }

        String text = textValue(root.get("text"));
        if (!StringUtils.hasText(text)) {
            return emitError(outbound, "BAD_REQUEST", "text is required for tts.chunk", activeTts.requestId);
        }

        return activeTts.ttsSession.appendText(text)
                .onErrorResume(ex -> failActiveTts(state, outbound, activeTts, ex));
    }

    private Mono<Void> handleTtsCommit(JsonNode root, SessionState state, Sinks.Many<OutboundFrame> outbound) {
        ActiveTts activeTts = state.activeTts;
        if (activeTts == null) {
            return emitError(outbound, "INVALID_STATE", "tts.start is required before tts.commit", null);
        }

        String requestId = textValue(root.get("requestId"));
        if (StringUtils.hasText(requestId) && !activeTts.requestId.equals(requestId.trim())) {
            return emitError(outbound, "INVALID_STATE", "requestId does not match current tts session", requestId);
        }

        Duration timeout = Duration.ofMillis(voiceTtsProperties.getAliyun().safeSessionFinishedTimeoutMs());
        return activeTts.ttsSession.commit()
                .then(activeTts.completion.timeout(timeout))
                .then(Mono.fromRunnable(() -> {
                    if (isCurrentActive(state, activeTts.requestId)) {
                        emitTtsDone(outbound, activeTts, "committed");
                        cleanupActiveTts(state, activeTts);
                    }
                }).then())
                .onErrorResume(ex -> failActiveTts(state, outbound, activeTts, ex));
    }

    private Mono<Void> handleTtsStop(JsonNode root, SessionState state, Sinks.Many<OutboundFrame> outbound) {
        ActiveTts activeTts = state.activeTts;
        if (activeTts == null) {
            return emitError(outbound, "INVALID_STATE", "no active tts session to stop", textValue(root.get("requestId")));
        }

        String requestId = textValue(root.get("requestId"));
        if (StringUtils.hasText(requestId) && !activeTts.requestId.equals(requestId.trim())) {
            return emitError(outbound, "INVALID_STATE", "requestId does not match current tts session", requestId);
        }

        return activeTts.ttsSession.stop()
                .then(Mono.fromRunnable(() -> {
                    if (isCurrentActive(state, activeTts.requestId)) {
                        emitTtsDone(outbound, activeTts, "stopped");
                        cleanupActiveTts(state, activeTts);
                    }
                }).then())
                .onErrorResume(ex -> failActiveTts(state, outbound, activeTts, ex));
    }

    private Mono<Void> handleAsrPlaceholder(
            String commandType,
            JsonNode root,
            SessionState state,
            Sinks.Many<OutboundFrame> outbound
    ) {
        if (state.activeTts != null) {
            emitTtsInterrupted(outbound, state.activeTts, commandType);
            cleanupActiveTts(state, state.activeTts);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "asr.not_implemented");
        payload.put("code", "NOT_IMPLEMENTED");
        payload.put("message", "asr pipeline is not implemented in current release");
        payload.put("command", commandType);
        payload.put("requestId", defaultedRequestId(root.get("requestId")));
        payload.put("chatId", textValue(root.get("chatId")));
        payload.put("timestamp", System.currentTimeMillis());
        emitText(outbound, payload);
        return Mono.empty();
    }

    private void emitTtsInterrupted(Sinks.Many<OutboundFrame> outbound, ActiveTts activeTts, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tts.interrupted");
        payload.put("requestId", activeTts.requestId);
        payload.put("chatId", activeTts.chatId);
        payload.put("reason", reason);
        payload.put("timestamp", System.currentTimeMillis());
        emitText(outbound, payload);
    }

    private void emitTtsDone(Sinks.Many<OutboundFrame> outbound, ActiveTts activeTts, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tts.done");
        payload.put("requestId", activeTts.requestId);
        payload.put("chatId", activeTts.chatId);
        payload.put("reason", reason);
        payload.put("timestamp", System.currentTimeMillis());
        emitText(outbound, payload);
    }

    private Mono<Void> emitError(
            Sinks.Many<OutboundFrame> outbound,
            String code,
            String message,
            String requestId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("code", code);
        payload.put("message", message);
        if (StringUtils.hasText(requestId)) {
            payload.put("requestId", requestId.trim());
        }
        payload.put("timestamp", System.currentTimeMillis());
        emitText(outbound, payload);
        return Mono.empty();
    }

    private Mono<Void> failActiveTts(
            SessionState state,
            Sinks.Many<OutboundFrame> outbound,
            ActiveTts activeTts,
            Throwable error
    ) {
        if (isCurrentActive(state, activeTts.requestId)) {
            cleanupActiveTts(state, activeTts);
            return emitError(outbound, PROVIDER_ERROR_CODE, providerErrorMessage(error), activeTts.requestId);
        }
        return Mono.empty();
    }

    private void interruptActiveTtsIfPresent(SessionState state, Sinks.Many<OutboundFrame> outbound, String reason) {
        ActiveTts activeTts = state.activeTts;
        if (activeTts == null) {
            return;
        }
        emitTtsInterrupted(outbound, activeTts, reason);
        cleanupActiveTts(state, activeTts);
    }

    private void cleanupActiveTts(SessionState state, ActiveTts activeTts) {
        if (activeTts == null) {
            return;
        }
        if (activeTts.audioSubscription != null && !activeTts.audioSubscription.isDisposed()) {
            activeTts.audioSubscription.dispose();
        }
        try {
            activeTts.ttsSession.close();
        } catch (Exception ignored) {
        }
        if (isCurrentActive(state, activeTts.requestId)) {
            state.activeTts = null;
        }
    }

    private void closeActiveIfPresent(SessionState state) {
        cleanupActiveTts(state, state.activeTts);
    }

    private boolean isCurrentActive(SessionState state, String requestId) {
        return state.activeTts != null && state.activeTts.requestId.equals(requestId);
    }

    private String providerErrorMessage(Throwable error) {
        if (error == null || !StringUtils.hasText(error.getMessage())) {
            return "tts provider request failed";
        }
        return error.getMessage();
    }

    private void emitText(Sinks.Many<OutboundFrame> outbound, Map<String, Object> payload) {
        outbound.tryEmitNext(new TextFrame(toJson(payload)));
    }

    private WebSocketMessage toWebSocketMessage(WebSocketSession session, OutboundFrame frame) {
        if (frame instanceof BinaryFrame binaryFrame) {
            return session.binaryMessage(factory -> factory.wrap(binaryFrame.payload));
        }
        return session.textMessage(((TextFrame) frame).json);
    }

    private String defaultedRequestId(JsonNode node) {
        String requestId = textValue(node);
        return StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
    }

    private String normalizeCodec(JsonNode node) {
        String codec = textValue(node);
        if (!StringUtils.hasText(codec)) {
            return "pcm";
        }
        return codec.trim().toLowerCase();
    }

    private int intValue(JsonNode node, int fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    private Integer boxedIntValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private Float boxedFloatValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return (float) node.asDouble();
    }

    private Boolean boxedBooleanValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asBoolean();
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean isValidSpeechRate(Float value) {
        return value == null || (value >= 0.5f && value <= 2.0f);
    }

    private boolean isValidPitchRate(Float value) {
        return value == null || (value >= 0.5f && value <= 2.0f);
    }

    private boolean isValidVolume(Integer value) {
        return value == null || (value >= 0 && value <= 100);
    }

    private boolean isValidBitRate(Integer value) {
        return value == null || (value >= 6 && value <= 510);
    }

    private boolean isValidTtsSampleRate(Integer value) {
        return value == null || value == 8000 || value == 16000 || value == 24000 || value == 48000;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"type\":\"error\",\"code\":\"BAD_REQUEST\",\"message\":\"serialization failed\"}";
        }
    }

    private sealed interface OutboundFrame permits TextFrame, BinaryFrame {
    }

    private record TextFrame(String json) implements OutboundFrame {
    }

    private record BinaryFrame(byte[] payload) implements OutboundFrame {
    }

    private static final class SessionState {
        private ActiveTts activeTts;
    }

    private record ActiveTts(
            String requestId,
            String chatId,
            String codec,
            String voice,
            int sampleRate,
            int channels,
            VoicePcmSynthesizer.TtsSession ttsSession,
            Disposable audioSubscription,
            Mono<Void> completion
    ) {
    }
}
