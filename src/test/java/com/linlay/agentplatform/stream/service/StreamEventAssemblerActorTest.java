package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventAssemblerActorTest {

    @Test
    void bootstrapAndRuntimeEventsShouldNotExposeActorFields() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_1",
                        "chat_1",
                        "user",
                        "hello",
                        "demoModePlain",
                        null,
                        null,
                        null,
                        null,
                        true,
                        false,
                        "chat_1",
                        "run_1"
                ));

        StreamEvent runStart = state.bootstrapEvents().stream()
                .filter(event -> "run.start".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(runStart.payload()).doesNotContainKeys("actorId", "actorType", "actorName");

        List<StreamEvent> contentEvents = state.consume(new StreamInput.ContentDelta("run_1_c_1", "hello", null));

        StreamEvent contentDelta = contentEvents.stream()
                .filter(event -> "content.delta".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(contentDelta.payload()).doesNotContainKeys("actorId", "actorType", "actorName");
    }
}
