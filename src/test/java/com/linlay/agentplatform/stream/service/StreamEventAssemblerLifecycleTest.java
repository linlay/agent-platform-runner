package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.chat.event.ArtifactEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventAssemblerLifecycleTest {

    @Test
    void completeShouldCloseOpenContentAndActiveTaskBeforeRunComplete() {
        StreamEventAssembler.EventStreamState state = newQueryState();
        state.consume(new StreamInput.PlanUpdate("plan_1", List.of(Map.of("taskId", "task_1")), "chat_1"));
        state.consume(new StreamInput.TaskStart("task_1", "run_1", "Task 1", "desc"));
        state.consume(new StreamInput.ContentDelta("content_1", "hello", "task_1"));

        List<StreamEvent> completionEvents = state.complete();

        assertThat(completionEvents).extracting(StreamEvent::type)
                .containsExactly("content.end", "task.complete", "run.complete");
        assertThat(completionEvents.get(2).payload()).containsEntry("finishReason", "end_turn");
    }

    @Test
    void runErrorShouldCloseOpenToolAndActionBeforeRunError() {
        StreamEventAssembler.EventStreamState state = newQueryState();
        state.consume(new StreamInput.PlanUpdate("plan_1", List.of(Map.of("taskId", "task_1")), "chat_1"));
        state.consume(new StreamInput.TaskStart("task_1", "run_1", "Task 1", "desc"));
        state.consume(new StreamInput.ToolArgs("tool_1", "{\"city\":\"Shanghai\"}", "task_1", "weather", "function", null, null, null));
        state.consume(new StreamInput.ActionArgs("action_1", "{\"theme\":\"dark\"}", "task_1", "switch_theme", "Switch theme"));

        Map<String, Object> error = Map.of("code", "run_timeout", "message", "timed out", "retriable", false);
        List<StreamEvent> errorEvents = state.consume(new StreamInput.RunError(error));

        assertThat(errorEvents).extracting(StreamEvent::type)
                .containsExactly("tool.end", "action.end", "task.fail", "run.error");
        assertThat(errorEvents.get(2).payload()).containsEntry("taskId", "task_1");
        assertThat(errorEvents.get(2).payload()).containsEntry("error", error);
        assertThat(errorEvents.get(3).payload()).containsEntry("runId", "run_1");
        assertThat(errorEvents.get(3).payload()).containsEntry("error", error);
    }

    @Test
    void failShouldCloseOpenReasoningAndEmitGeneratedRunErrorPayload() {
        StreamEventAssembler.EventStreamState state = newQueryState();
        state.consume(new StreamInput.PlanUpdate("plan_1", List.of(Map.of("taskId", "task_1")), "chat_1"));
        state.consume(new StreamInput.TaskStart("task_1", "run_1", "Task 1", "desc"));
        state.consume(new StreamInput.ReasoningDelta("reasoning_1", "thinking", "task_1"));

        List<StreamEvent> errorEvents = state.fail(new IllegalStateException("boom"));

        assertThat(errorEvents).extracting(StreamEvent::type)
                .containsExactly("reasoning.end", "task.fail", "run.error");
        @SuppressWarnings("unchecked")
        Map<String, Object> errorPayload = (Map<String, Object>) errorEvents.get(2).payload().get("error");
        assertThat(errorPayload).containsEntry("code", "RUN_ERROR");
        assertThat(errorPayload).containsEntry("message", "boom");
        assertThat(errorPayload).containsEntry("retriable", false);
    }

    @Test
    void artifactPublishShouldEmitIndependentRunScopedEvent() {
        StreamEventAssembler.EventStreamState state = newQueryState();
        ArtifactEventPayload artifact = new ArtifactEventPayload(
                "file",
                "report.md",
                "text/markdown",
                12L,
                "/api/resource?file=chat_1%2Fartifacts%2Frun_1%2Freport.md",
                null
        );

        List<StreamEvent> events = state.consume(new StreamInput.ArtifactPublish(
                "asset_1",
                "chat_1",
                "run_1",
                artifact
        ));

        assertThat(events).extracting(StreamEvent::type).containsExactly("artifact.publish");
        assertThat(events.getFirst().payload()).containsEntry("artifactId", "asset_1");
        assertThat(events.getFirst().payload()).containsEntry("chatId", "chat_1");
        assertThat(events.getFirst().payload()).containsEntry("runId", "run_1");
    }

    private StreamEventAssembler.EventStreamState newQueryState() {
        return new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_1",
                        "chat_1",
                        "user",
                        "test",
                        "demoModePlain",
                        null,
                        null,
                        null,
                        null,
                        true,
                        "chat_1",
                        "run_1"
                ));
    }
}
