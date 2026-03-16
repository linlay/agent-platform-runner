package com.linlay.agentplatform.stream.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.H2aProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class RenderQueueTest {

    @Test
    void bufferShouldFlushWhenBufferedEventThresholdIsReached() {
        H2aProperties properties = new H2aProperties();
        properties.getRender().setMaxBufferedEvents(2);
        RenderQueue queue = new RenderQueue(new ObjectMapper(), properties);

        Flux<ServerSentEvent<String>> buffered = queue.buffer(Flux.just(
                event("{\"type\":\"content.delta\",\"delta\":\"a\"}"),
                event("{\"type\":\"content.delta\",\"delta\":\"b\"}")
        ));

        StepVerifier.create(buffered)
                .expectNextMatches(event -> event.data().contains("\"delta\":\"a\""))
                .expectNextMatches(event -> event.data().contains("\"delta\":\"b\""))
                .verifyComplete();
    }

    @Test
    void bufferShouldFlushPendingEventsBeforeTerminalEvent() {
        H2aProperties properties = new H2aProperties();
        properties.getRender().setFlushIntervalMs(60_000L);
        RenderQueue queue = new RenderQueue(new ObjectMapper(), properties);

        Flux<ServerSentEvent<String>> buffered = queue.buffer(Flux.just(
                event("{\"type\":\"content.delta\",\"delta\":\"hello\"}"),
                event("{\"type\":\"run.complete\",\"runId\":\"run_1\"}")
        ));

        StepVerifier.create(buffered)
                .expectNextMatches(event -> event.data().contains("\"content.delta\""))
                .expectNextMatches(event -> event.data().contains("\"run.complete\""))
                .verifyComplete();
    }

    private ServerSentEvent<String> event(String data) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(data)
                .build();
    }
}
