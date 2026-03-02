package com.linlay.agentplatform.voice.ws;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;
import com.linlay.agentplatform.config.VoiceTtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AliyunRealtimeVoicePcmSynthesizer implements VoicePcmSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(AliyunRealtimeVoicePcmSynthesizer.class);

    private final VoiceTtsProperties voiceTtsProperties;

    public AliyunRealtimeVoicePcmSynthesizer(VoiceTtsProperties voiceTtsProperties) {
        this.voiceTtsProperties = Objects.requireNonNull(voiceTtsProperties, "voiceTtsProperties must not be null");
    }

    @Override
    public TtsSession startSession(StartRequest request) {
        VoiceTtsProperties.AliyunProperties aliyun = voiceTtsProperties.getAliyun();
        String endpoint = requireConfigured(aliyun.getEndpoint(), "agent.voice.tts.aliyun.endpoint");
        String apiKey = requireConfigured(aliyun.getApiKey(), "agent.voice.tts.aliyun.api-key");
        String model = requireConfigured(aliyun.getModel(), "agent.voice.tts.aliyun.model");
        String voice = requireConfigured(firstNonBlank(request == null ? null : request.voice(), aliyun.getVoice()),
                "agent.voice.tts.aliyun.voice");
        String mode = firstNonBlank(aliyun.getMode(), "server_commit");
        String instructions = aliyun.getInstructions();
        QwenTtsRealtimeAudioFormat format = resolveAudioFormat(aliyun.getResponseFormat());

        Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<QwenTtsRealtime> realtimeRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
            @Override
            public void onOpen() {
                log.debug("Aliyun realtime TTS websocket opened");
            }

            @Override
            public void onEvent(JsonObject message) {
                try {
                    String type = message.has("type") ? message.get("type").getAsString() : "unknown";
                    if ("response.audio.delta".equals(type) && message.has("delta")) {
                        byte[] pcm = Base64.getDecoder().decode(message.get("delta").getAsString());
                        sink.tryEmitNext(pcm);
                        return;
                    }
                    if ("session.finished".equals(type)) {
                        sink.tryEmitComplete();
                        return;
                    }
                    if ("response.error".equals(type)) {
                        sink.tryEmitError(new IllegalStateException("aliyun realtime tts response error"));
                    }
                } catch (Exception ex) {
                    sink.tryEmitError(new IllegalStateException("aliyun realtime tts callback failed", ex));
                }
            }

            @Override
            public void onClose(int code, String reason) {
                if (!closed.get()) {
                    sink.tryEmitComplete();
                }
                log.debug("Aliyun realtime TTS websocket closed: code={}, reason={}", code, reason);
            }
        };

        try {
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(model)
                    .url(endpoint)
                    .apikey(apiKey)
                    .build();
            QwenTtsRealtime realtime = new QwenTtsRealtime(param, callback);
            realtime.connect();
            realtime.updateSession(buildSessionConfig(format, voice, mode, instructions));
            realtimeRef.set(realtime);
        } catch (NoApiKeyException ex) {
            throw new IllegalStateException("aliyun tts api-key is missing or invalid", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("aliyun realtime tts session start failed", ex);
        }

        return new AliyunSession(realtimeRef, sink, closed, aliyun.isLogSentChunkEnabled());
    }

    private QwenTtsRealtimeAudioFormat resolveAudioFormat(String format) {
        String normalized = requireConfigured(format, "agent.voice.tts.aliyun.response-format").trim().toUpperCase();
        try {
            return QwenTtsRealtimeAudioFormat.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("unsupported aliyun response format: " + format, ex);
        }
    }

    private QwenTtsRealtimeConfig buildSessionConfig(
            QwenTtsRealtimeAudioFormat format,
            String voice,
            String mode,
            String instructions
    ) {
        QwenTtsRealtimeConfig.QwenTtsRealtimeConfigBuilder<?, ?> builder = QwenTtsRealtimeConfig.builder()
                .voice(voice)
                .responseFormat(format)
                .mode(mode);
        if (StringUtils.hasText(instructions)) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("instructions", instructions.trim());
            parameters.put("optimize_instructions", true);
            builder.parameters(parameters);
        }
        return builder.build();
    }

    private String requireConfigured(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("missing configuration: " + name);
        }
        return value.trim();
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return fallback;
    }

    private static final class AliyunSession implements TtsSession {

        private final AtomicReference<QwenTtsRealtime> realtimeRef;
        private final Sinks.Many<byte[]> sink;
        private final AtomicBoolean closed;
        private final boolean logSentChunkEnabled;

        private AliyunSession(
                AtomicReference<QwenTtsRealtime> realtimeRef,
                Sinks.Many<byte[]> sink,
                AtomicBoolean closed,
                boolean logSentChunkEnabled
        ) {
            this.realtimeRef = realtimeRef;
            this.sink = sink;
            this.closed = closed;
            this.logSentChunkEnabled = logSentChunkEnabled;
        }

        @Override
        public Flux<byte[]> audioFlux() {
            return sink.asFlux();
        }

        @Override
        public Mono<Void> appendText(String text) {
            return Mono.fromRunnable(() -> {
                        if (closed.get()) {
                            throw new IllegalStateException("tts session is already closed");
                        }
                        if (!StringUtils.hasText(text)) {
                            return;
                        }
                        if (logSentChunkEnabled) {
                            log.info("[ALIYUN-TTS-SENT-CHUNK] {}", text.trim());
                        }
                        QwenTtsRealtime realtime = requireRealtime();
                        realtime.appendText(text);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(ex -> new IllegalStateException("aliyun realtime tts append failed", ex))
                    .then();
        }

        @Override
        public Mono<Void> commit() {
            return Mono.fromRunnable(() -> {
                        if (closed.get()) {
                            throw new IllegalStateException("tts session is already closed");
                        }
                        QwenTtsRealtime realtime = requireRealtime();
                        realtime.finish();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(ex -> new IllegalStateException("aliyun realtime tts commit failed", ex))
                    .then();
        }

        @Override
        public Mono<Void> stop() {
            return Mono.fromRunnable(this::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    .then();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            QwenTtsRealtime realtime = realtimeRef.getAndSet(null);
            if (realtime != null) {
                try {
                    realtime.close();
                } catch (Exception closeEx) {
                    log.warn("aliyun realtime tts close failed", closeEx);
                }
            }
            sink.tryEmitComplete();
        }

        private QwenTtsRealtime requireRealtime() {
            QwenTtsRealtime realtime = realtimeRef.get();
            if (realtime == null) {
                throw new IllegalStateException("aliyun realtime tts session is unavailable");
            }
            return realtime;
        }
    }
}
