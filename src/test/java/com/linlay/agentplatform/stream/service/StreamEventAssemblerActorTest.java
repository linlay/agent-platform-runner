package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.RunActor;
import com.linlay.agentplatform.stream.model.StreamEnvelope;
import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventAssemblerActorTest {

    @Test
    void bootstrapAndRuntimeEventsShouldExposeActorFields() {
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
                        "run_1",
                        RunActor.commander("commander_1", "Commander")
                ));

        StreamEvent runStart = state.bootstrapEvents().stream()
                .filter(event -> "run.start".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(runStart.payload()).containsEntry("actorId", "commander_1");
        assertThat(runStart.payload()).containsEntry("actorType", "commander");
        assertThat(runStart.payload()).containsEntry("actorName", "Commander");

        List<StreamEvent> contentEvents = state.consume(StreamEnvelope.of(
                new StreamInput.ContentDelta("run_1_c_1", "hello", null),
                RunActor.subagent("worker_1", "Worker")
        ));

        StreamEvent contentDelta = contentEvents.stream()
                .filter(event -> "content.delta".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(contentDelta.payload()).containsEntry("actorId", "worker_1");
        assertThat(contentDelta.payload()).containsEntry("actorType", "subagent");
        assertThat(contentDelta.payload()).containsEntry("actorName", "Worker");
    }
}
