package com.linlay.agentplatform.chat.event;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventFactoryTest {

    @Test
    void shouldBuildStableEventShape() {
        Map<String, Object> event = SseEventFactory.event(
                "run.complete",
                123L,
                7L,
                Map.of("runId", "run_1")
        );

        assertThat(event).containsEntry("seq", 7L);
        assertThat(event).containsEntry("type", "run.complete");
        assertThat(event).containsEntry("timestamp", 123L);
        assertThat(event).containsEntry("runId", "run_1");
    }
}
