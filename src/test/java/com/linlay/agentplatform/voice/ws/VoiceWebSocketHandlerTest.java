package com.linlay.agentplatform.voice.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.VoiceTtsProperties;
import com.linlay.agentplatform.config.VoiceWsProperties;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ttsStartChunkCommitShouldEmitPcmAndDone() {
        VoiceWebSocketHandler handler = createHandler(false, true, new TestVoicePcmSynthesizer(false));
        TestWebSocketSession session = new TestWebSocketSession(List.of(
                json(Map.of("type", "tts.start", "requestId", "r-1", "codec", "pcm", "sampleRate", 24000, "channels", 1)),
                json(Map.of("type", "tts.chunk", "requestId", "r-1", "seq", 1, "text", "你好")),
                json(Map.of("type", "tts.commit", "requestId", "r-1"))
        ));

        handler.handle(session).block(Duration.ofSeconds(5));

        assertThat(eventTypes(session.textPayloads())).contains("tts.started", "tts.done");
        assertThat(session.binaryPayloadSizes()).isNotEmpty();
    }

    @Test
    void ttsOpusShouldReturnUnsupportedCodecError() {
        VoiceWebSocketHandler handler = createHandler(false, true, new TestVoicePcmSynthesizer(false));
        TestWebSocketSession session = new TestWebSocketSession(List.of(
                json(Map.of("type", "tts.start", "requestId", "r-opus", "codec", "opus"))
        ));

        handler.handle(session).block(Duration.ofSeconds(5));

        assertThat(eventTypes(session.textPayloads())).contains("error");
        assertThat(errorCodes(session.textPayloads())).contains("UNSUPPORTED_CODEC");
    }

    @Test
    void asrStartShouldInterruptTtsAndReturnNotImplemented() {
        VoiceWebSocketHandler handler = createHandler(false, true, new TestVoicePcmSynthesizer(false));
        TestWebSocketSession session = new TestWebSocketSession(List.of(
                json(Map.of("type", "tts.start", "requestId", "r-2", "chatId", "123e4567-e89b-12d3-a456-426614174000", "codec", "pcm")),
                json(Map.of("type", "tts.chunk", "requestId", "r-2", "seq", 1, "text", "继续播放")),
                json(Map.of("type", "asr.start", "requestId", "r-asr", "chatId", "123e4567-e89b-12d3-a456-426614174000"))
        ));

        handler.handle(session).block(Duration.ofSeconds(5));

        assertThat(eventTypes(session.textPayloads())).contains("tts.started", "tts.interrupted", "asr.not_implemented");
    }

    @Test
    void missingBearerShouldReturnUnauthorizedWhenAuthEnabledAndVoiceWsAuthRequired() {
        VoiceWebSocketHandler handler = createHandler(true, true, new TestVoicePcmSynthesizer(false));
        TestWebSocketSession session = new TestWebSocketSession(List.of(
                json(Map.of("type", "tts.start", "requestId", "r-auth", "codec", "pcm"))
        ));

        handler.handle(session).block(Duration.ofSeconds(5));

        assertThat(eventTypes(session.textPayloads())).contains("error");
        assertThat(errorCodes(session.textPayloads())).contains("UNAUTHORIZED");
        assertThat(session.closedStatus).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void providerFailureShouldReturnTtsProviderError() {
        VoiceWebSocketHandler handler = createHandler(false, true, new TestVoicePcmSynthesizer(true));
        TestWebSocketSession session = new TestWebSocketSession(List.of(
                json(Map.of("type", "tts.start", "requestId", "r-fail", "codec", "pcm")),
                json(Map.of("type", "tts.chunk", "requestId", "r-fail", "seq", 1, "text", "触发失败"))
        ));

        handler.handle(session).block(Duration.ofSeconds(5));

        assertThat(errorCodes(session.textPayloads())).contains("TTS_PROVIDER_ERROR");
    }

    private VoiceWebSocketHandler createHandler(boolean authEnabled, boolean wsAuthRequired, VoicePcmSynthesizer synthesizer) {
        VoiceWsProperties voiceWsProperties = new VoiceWsProperties();
        voiceWsProperties.setMaxSessionSeconds(30);
        voiceWsProperties.setAuthRequired(wsAuthRequired);
        voiceWsProperties.setCodecs(List.of("pcm", "opus"));

        VoiceTtsProperties voiceTtsProperties = new VoiceTtsProperties();
        voiceTtsProperties.getAliyun().setSessionFinishedTimeoutMs(3000L);

        AppAuthProperties authProperties = new AppAuthProperties();
        authProperties.setEnabled(authEnabled);
        VoiceWsAuthenticationService authenticationService = new VoiceWsAuthenticationService(
                authProperties,
                voiceWsProperties,
                new JwksJwtVerifier(authProperties)
        );
        return new VoiceWebSocketHandler(
                objectMapper,
                voiceWsProperties,
                voiceTtsProperties,
                authenticationService,
                synthesizer
        );
    }

    private List<String> eventTypes(List<String> textPayloads) {
        List<String> types = new ArrayList<>();
        for (String textPayload : textPayloads) {
            try {
                JsonNode root = objectMapper.readTree(textPayload);
                String type = root.path("type").asText(null);
                if (type != null) {
                    types.add(type);
                }
            } catch (Exception ignored) {
            }
        }
        return types;
    }

    private List<String> errorCodes(List<String> textPayloads) {
        List<String> codes = new ArrayList<>();
        for (String textPayload : textPayloads) {
            try {
                JsonNode root = objectMapper.readTree(textPayload);
                String code = root.path("code").asText(null);
                if (code != null) {
                    codes.add(code);
                }
            } catch (Exception ignored) {
            }
        }
        return codes;
    }

    private String json(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(map));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize payload", ex);
        }
    }

    private static final class TestVoicePcmSynthesizer implements VoicePcmSynthesizer {

        private final boolean failOnAppend;

        private TestVoicePcmSynthesizer(boolean failOnAppend) {
            this.failOnAppend = failOnAppend;
        }

        @Override
        public TtsSession startSession(StartRequest request) {
            return new TestSession(failOnAppend);
        }
    }

    private static final class TestSession implements VoicePcmSynthesizer.TtsSession {

        private final Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        private final boolean failOnAppend;

        private TestSession(boolean failOnAppend) {
            this.failOnAppend = failOnAppend;
        }

        @Override
        public Flux<byte[]> audioFlux() {
            return sink.asFlux();
        }

        @Override
        public Mono<Void> appendText(String text) {
            if (failOnAppend) {
                return Mono.error(new IllegalStateException("forced append failure"));
            }
            sink.tryEmitNext(new byte[]{1, 2, 3, 4});
            return Mono.empty();
        }

        @Override
        public Mono<Void> commit() {
            sink.tryEmitComplete();
            return Mono.empty();
        }

        @Override
        public Mono<Void> stop() {
            sink.tryEmitComplete();
            return Mono.empty();
        }

        @Override
        public void close() {
            sink.tryEmitComplete();
        }
    }

    private static final class TestWebSocketSession implements WebSocketSession {
        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        private final HandshakeInfo handshakeInfo = new HandshakeInfo(
                URI.create("ws://localhost/api/ap/ws/voice"),
                new HttpHeaders(),
                Mono.empty(),
                null
        );
        private final Flux<WebSocketMessage> inbound;
        private final List<WebSocketMessage> outbound = new CopyOnWriteArrayList<>();
        private volatile CloseStatus closedStatus;

        private TestWebSocketSession(List<String> payloads) {
            this.inbound = Flux.fromIterable(payloads).map(this::textMessage);
        }

        @Override
        public String getId() {
            return "voice-test-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return handshakeInfo;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return inbound;
        }

        @Override
        public Mono<Void> send(org.reactivestreams.Publisher<WebSocketMessage> messages) {
            return Flux.from(messages).doOnNext(outbound::add).then();
        }

        @Override
        public boolean isOpen() {
            return closedStatus == null;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            this.closedStatus = status;
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.justOrEmpty(closedStatus);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return new WebSocketMessage(WebSocketMessage.Type.TEXT, bufferFactory.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public WebSocketMessage binaryMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pingMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory));
        }

        @Override
        public WebSocketMessage pongMessage(java.util.function.Function<DataBufferFactory, DataBuffer> payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory));
        }

        private List<String> textPayloads() {
            return outbound.stream()
                    .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                    .map(WebSocketMessage::getPayloadAsText)
                    .toList();
        }

        private List<Integer> binaryPayloadSizes() {
            return outbound.stream()
                    .filter(message -> message.getType() == WebSocketMessage.Type.BINARY)
                    .map(message -> message.getPayload().readableByteCount())
                    .toList();
        }
    }
}
