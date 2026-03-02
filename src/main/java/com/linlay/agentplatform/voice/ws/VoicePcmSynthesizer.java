package com.linlay.agentplatform.voice.ws;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VoicePcmSynthesizer {

    TtsSession startSession(StartRequest request);

    interface TtsSession extends AutoCloseable {

        Flux<byte[]> audioFlux();

        Mono<Void> appendText(String text);

        Mono<Void> commit();

        Mono<Void> stop();

        @Override
        void close();
    }

    record StartRequest(
            String requestId,
            String chatId,
            int sampleRate,
            int channels,
            String voice,
            SessionOptions options
    ) {
    }

    record SessionOptions(
            String languageType,
            String mode,
            String format,
            Integer sampleRate,
            Float speechRate,
            Integer volume,
            Float pitchRate,
            Integer bitRate,
            String instructions,
            Boolean optimizeInstructions,
            String responseFormat
    ) {
    }
}
