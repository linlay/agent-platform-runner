package com.linlay.agentplatform.voice.ws;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyntheticVoicePcmSynthesizer implements VoicePcmSynthesizer {

    private static final int CHAR_DURATION_MS = 45;
    private static final double AMPLITUDE = 0.22d;

    @Override
    public TtsSession startSession(StartRequest request) {
        int safeSampleRate = request != null && request.sampleRate() > 0 ? request.sampleRate() : 24000;
        int safeChannels = request != null && request.channels() > 0 ? request.channels() : 1;
        Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        return new SyntheticTtsSession(safeSampleRate, safeChannels, sink);
    }

    private List<byte[]> synthesizePcmFrames(String text, int sampleRate, int channels) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int safeSampleRate = sampleRate > 0 ? sampleRate : 24000;
        int safeChannels = channels > 0 ? channels : 1;
        int samplesPerChar = Math.max(1, safeSampleRate * CHAR_DURATION_MS / 1000);
        ByteBuffer pcm = ByteBuffer.allocate(text.length() * samplesPerChar * safeChannels * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int charIndex = 0; charIndex < text.length(); charIndex++) {
            char ch = text.charAt(charIndex);
            double frequencyHz = 220.0d + (ch % 24) * 18.0d;
            for (int i = 0; i < samplesPerChar; i++) {
                double phase = 2.0d * Math.PI * frequencyHz * i / safeSampleRate;
                short value = (short) Math.round(Math.sin(phase) * Short.MAX_VALUE * AMPLITUDE);
                for (int channel = 0; channel < safeChannels; channel++) {
                    pcm.putShort(value);
                }
            }
        }

        byte[] payload = pcm.array();
        int frameSize = Math.max(640, safeSampleRate / 50 * safeChannels * Short.BYTES);
        List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < payload.length; offset += frameSize) {
            int end = Math.min(offset + frameSize, payload.length);
            frames.add(Arrays.copyOfRange(payload, offset, end));
        }
        return frames;
    }

    private final class SyntheticTtsSession implements TtsSession {

        private final int sampleRate;
        private final int channels;
        private final Sinks.Many<byte[]> sink;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private SyntheticTtsSession(int sampleRate, int channels, Sinks.Many<byte[]> sink) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.sink = sink;
        }

        @Override
        public Flux<byte[]> audioFlux() {
            return sink.asFlux();
        }

        @Override
        public Mono<Void> appendText(String text) {
            if (closed.get()) {
                return Mono.error(new IllegalStateException("tts session is closed"));
            }
            for (byte[] frame : synthesizePcmFrames(text, sampleRate, channels)) {
                sink.tryEmitNext(frame);
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> commit() {
            if (closed.compareAndSet(false, true)) {
                sink.tryEmitComplete();
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> stop() {
            if (closed.compareAndSet(false, true)) {
                sink.tryEmitComplete();
            }
            return Mono.empty();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                sink.tryEmitComplete();
            }
        }
    }
}
