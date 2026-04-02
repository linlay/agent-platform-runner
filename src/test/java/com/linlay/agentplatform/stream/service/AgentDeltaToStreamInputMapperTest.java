package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.linlay.agentplatform.stream.service.StreamEventAssembler;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ArtifactEventPayload;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDeltaToStreamInputMapperTest {

    @Test
    void shouldEmitActionEndBeforeActionResult() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "action_1",
                        "action",
                        "switch_theme",
                        "{\"theme\":\"dark\"}"
                ))),
                AgentDelta.toolResult("action_1", "OK")
        ));

        int actionStart = indexOfActionEvent(events, "action.start", "action_1");
        int actionArgs = indexOfActionEvent(events, "action.args", "action_1");
        int actionEnd = indexOfActionEvent(events, "action.end", "action_1");
        int actionResult = indexOfActionEvent(events, "action.result", "action_1");

        assertThat(actionStart).isGreaterThanOrEqualTo(0);
        assertThat(actionArgs).isGreaterThan(actionStart);
        assertThat(actionEnd).isGreaterThan(actionArgs);
        assertThat(actionResult).isGreaterThan(actionEnd);
        assertThat(countActionEvent(events, "action.end", "action_1")).isEqualTo(1);
    }

    @Test
    void shouldKeepToolEndBeforeToolResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.label("bash")).thenReturn("命令执行");
        when(toolRegistry.description("bash")).thenReturn("执行 shell 命令");
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, toolRegistry, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "bash",
                        "{\"command\":\"ls\"}"
                ))),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}")
        ));

        int toolStart = indexOfToolEvent(events, "tool.start", "tool_1");
        int toolArgs = indexOfToolEvent(events, "tool.args", "tool_1");
        int toolEnd = indexOfToolEvent(events, "tool.end", "tool_1");
        int toolResult = indexOfToolEvent(events, "tool.result", "tool_1");

        assertThat(toolStart).isGreaterThanOrEqualTo(0);
        assertThat(toolArgs).isGreaterThan(toolStart);
        assertThat(toolEnd).isGreaterThan(toolArgs);
        assertThat(toolResult).isGreaterThan(toolEnd);
        assertThat(countToolEvent(events, "tool.end", "tool_1")).isEqualTo(1);
        StreamEvent toolStartEvent = events.get(toolStart);
        assertThat(toolStartEvent.payload()).containsEntry("toolLabel", "命令执行");
        assertThat(toolStartEvent.payload()).containsEntry("toolDescription", "执行 shell 命令");
        assertThat(toolStartEvent.payload()).doesNotContainKey("toolParams");
    }

    @Test
    void shouldNotEmitDuplicateToolEndWhenExplicitToolEndProvided() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "bash",
                        "{\"command\":\"ls\"}"
                ))),
                AgentDelta.toolEnd("tool_1"),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}")
        ));

        int toolStart = indexOfToolEvent(events, "tool.start", "tool_1");
        int toolArgs = indexOfToolEvent(events, "tool.args", "tool_1");
        int toolEnd = indexOfToolEvent(events, "tool.end", "tool_1");
        int toolResult = indexOfToolEvent(events, "tool.result", "tool_1");

        assertThat(toolStart).isGreaterThanOrEqualTo(0);
        assertThat(toolArgs).isGreaterThan(toolStart);
        assertThat(toolEnd).isGreaterThan(toolArgs);
        assertThat(toolResult).isGreaterThan(toolEnd);
        assertThat(countToolEvent(events, "tool.end", "tool_1")).isEqualTo(1);
    }

    @Test
    void shouldPreserveToolArgsChunkOrderWithoutMerging() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_chunk_1",
                        "function",
                        "bash",
                        "{\"command\":\"ec"
                ))),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_chunk_1",
                        "function",
                        null,
                        "ho hi\"}"
                ))),
                AgentDelta.toolResult("tool_chunk_1", "{\"ok\":true}")
        ));

        List<StreamEvent> toolArgsEvents = events.stream()
                .filter(event -> "tool.args".equals(event.type()))
                .filter(event -> "tool_chunk_1".equals(String.valueOf(event.payload().get("toolId"))))
                .toList();

        assertThat(toolArgsEvents).hasSize(2);
        assertThat(String.valueOf(toolArgsEvents.get(0).payload().get("delta"))).isEqualTo("{\"command\":\"ec");
        assertThat(String.valueOf(toolArgsEvents.get(1).payload().get("delta"))).isEqualTo("ho hi\"}");
        assertThat(Integer.parseInt(String.valueOf(toolArgsEvents.get(0).payload().get("chunkIndex")))).isEqualTo(0);
        assertThat(Integer.parseInt(String.valueOf(toolArgsEvents.get(1).payload().get("chunkIndex")))).isEqualTo(1);
    }

    @Test
    void shouldMapExplicitToolEndForActionsWithoutDuplicatingActionEndOnResult() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "action_1",
                        "action",
                        "show_modal",
                        "{\"title\":\"马年诗\"}"
                ))),
                AgentDelta.toolEnd("action_1"),
                AgentDelta.toolResult("action_1", "OK")
        ));

        int actionArgs = indexOfActionEvent(events, "action.args", "action_1");
        int actionEnd = indexOfActionEvent(events, "action.end", "action_1");
        int actionResult = indexOfActionEvent(events, "action.result", "action_1");

        assertThat(actionArgs).isGreaterThanOrEqualTo(0);
        assertThat(actionEnd).isGreaterThan(actionArgs);
        assertThat(actionResult).isGreaterThan(actionEnd);
        assertThat(countActionEvent(events, "action.end", "action_1")).isEqualTo(1);
    }

    @Test
    void shouldKeepActionEndBeforeNextActionStartWhenEndsAreExplicit() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "action_1",
                        "action",
                        "show_modal",
                        "{\"title\":\"马年诗\"}"
                ))),
                AgentDelta.toolEnd("action_1"),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "action_2",
                        "action",
                        "launch_fireworks",
                        "{\"durationMs\":5000}"
                ))),
                AgentDelta.toolEnd("action_2"),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "action_3",
                        "action",
                        "switch_theme",
                        "{\"theme\":\"dark\"}"
                ))),
                AgentDelta.toolEnd("action_3"),
                AgentDelta.toolResult("action_1", "OK"),
                AgentDelta.toolResult("action_2", "OK"),
                AgentDelta.toolResult("action_3", "OK")
        ));

        int action1End = indexOfActionEvent(events, "action.end", "action_1");
        int action2Start = indexOfActionEvent(events, "action.start", "action_2");
        int action2End = indexOfActionEvent(events, "action.end", "action_2");
        int action3Start = indexOfActionEvent(events, "action.start", "action_3");
        int action3End = indexOfActionEvent(events, "action.end", "action_3");
        int action1Result = indexOfActionEvent(events, "action.result", "action_1");

        assertThat(action1End).isGreaterThan(indexOfActionEvent(events, "action.args", "action_1"));
        assertThat(action1End).isLessThan(action2Start);
        assertThat(action2End).isLessThan(action3Start);
        assertThat(action3End).isLessThan(action1Result);
        assertThat(countActionEvent(events, "action.end", "action_1")).isEqualTo(1);
        assertThat(countActionEvent(events, "action.end", "action_2")).isEqualTo(1);
        assertThat(countActionEvent(events, "action.end", "action_3")).isEqualTo(1);
    }

    @Test
    void shouldAssignUniqueReasoningIdsAcrossBlocks() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.reasoning("reasoning-1"),
                AgentDelta.stageMarker("stage-1"),
                AgentDelta.reasoning("reasoning-2")
        ));

        List<String> reasoningStartIds = payloadValues(events, "reasoning.start", "reasoningId");
        assertThat(reasoningStartIds).containsExactly("run_1_r_1", "run_1_r_2");
    }

    @Test
    void shouldAssignUniqueContentIdsAcrossMultipleSegments() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.content("content-1"),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "bash",
                        "{\"command\":\"pwd\"}"
                ))),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}"),
                AgentDelta.content("content-2")
        ));

        List<String> contentStartIds = payloadValues(events, "content.start", "contentId");
        assertThat(contentStartIds).containsExactly("run_1_c_1", "run_1_c_2");
    }

    @Test
    void shouldRotateReasoningIdAfterToolBoundary() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.reasoning("before-tool"),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "bash",
                        "{\"command\":\"ls\"}"
                ))),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}"),
                AgentDelta.reasoning("after-tool")
        ));

        List<String> reasoningStartIds = payloadValues(events, "reasoning.start", "reasoningId");
        assertThat(reasoningStartIds).containsExactly("run_1_r_1", "run_1_r_2");
    }

    @Test
    void shouldUseNewContentIdAfterStageMarker() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.content("before-stage"),
                AgentDelta.stageMarker("summary"),
                AgentDelta.content("after-stage")
        ));

        List<String> contentStartIds = payloadValues(events, "content.start", "contentId");
        assertThat(contentStartIds).containsExactly("run_1_c_1", "run_1_c_2");
    }

    @Test
    void shouldPreferIdsProvidedByAgentDelta() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.reasoning("reasoning-1").withReasoningId("run_1_r_9"),
                AgentDelta.content("content-1").withContentId("run_1_c_7")
        ));

        assertThat(payloadValues(events, "reasoning.start", "reasoningId")).containsExactly("run_1_r_9");
        assertThat(payloadValues(events, "content.start", "contentId")).containsExactly("run_1_c_7");
    }

    @Test
    void shouldMapPlanUpdateWithSinglePlanEventType() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.planUpdate(
                        "plan_demo_001",
                        "chat_1",
                        List.of(
                                new AgentDelta.PlanTask("task0", "收集信息", "init"),
                                new AgentDelta.PlanTask("task1", "执行任务", "init")
                        )
                )
        ));

        List<StreamEvent> planEvents = events.stream()
                .filter(event -> event.type() != null && event.type().startsWith("plan."))
                .toList();

        assertThat(planEvents).hasSize(1);
        assertThat(planEvents.getFirst().type()).isEqualTo("plan.update");
        assertThat(planEvents.getFirst().payload().get("planId")).isEqualTo("plan_demo_001");
        assertThat(planEvents.getFirst().payload().get("chatId")).isEqualTo("chat_1");
        assertThat(planEvents.getFirst().payload().get("plan")).isInstanceOf(List.class);
    }

    @Test
    void shouldMapTaskLifecycleAndBindTaskIdToLeafEvents() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.planUpdate(
                        "plan_1",
                        "chat_1",
                        List.of(new AgentDelta.PlanTask("task_1", "执行任务", "init"))
                ),
                AgentDelta.taskStart("task_1", "run_1", "task_1", "执行任务"),
                AgentDelta.reasoning("先分析", "task_1"),
                AgentDelta.content("再输出", "task_1"),
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "bash",
                        "{\"command\":\"pwd\"}"
                )), "task_1"),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}"),
                AgentDelta.taskComplete("task_1")
        ));

        int taskStart = indexOfEvent(events, "task.start", "taskId", "task_1");
        int taskComplete = indexOfEvent(events, "task.complete", "taskId", "task_1");
        int reasoningStart = indexOfEvent(events, "reasoning.start", "taskId", "task_1");
        int contentStart = indexOfEvent(events, "content.start", "taskId", "task_1");
        int toolStart = indexOfEvent(events, "tool.start", "taskId", "task_1");

        assertThat(taskStart).isGreaterThanOrEqualTo(0);
        assertThat(reasoningStart).isGreaterThan(taskStart);
        assertThat(contentStart).isGreaterThan(taskStart);
        assertThat(toolStart).isGreaterThan(taskStart);
        assertThat(taskComplete).isGreaterThan(toolStart);
    }

    @Test
    void shouldMapTaskFailEventWithErrorPayload() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.planUpdate(
                        "plan_1",
                        "chat_1",
                        List.of(new AgentDelta.PlanTask("task_1", "执行任务", "init"))
                ),
                AgentDelta.taskStart("task_1", "run_1", "task_1", "执行任务"),
                AgentDelta.taskFail("task_1", Map.of("code", "task_execution_error", "message", "boom"))
        ));

        int failIndex = indexOfEvent(events, "task.fail", "taskId", "task_1");
        assertThat(failIndex).isGreaterThanOrEqualTo(0);
        StreamEvent failEvent = events.get(failIndex);
        assertThat(failEvent.payload().get("error")).isInstanceOf(Map.class);
        Map<?, ?> error = (Map<?, ?>) failEvent.payload().get("error");
        assertThat(error.get("code")).isEqualTo("task_execution_error");
        assertThat(error.get("message")).isEqualTo("boom");
    }

    @Test
    void shouldMapRequestSubmitEvent() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.requestSubmit(
                        "req_submit_1",
                        "chat_1",
                        "run_1",
                        "call_frontend_1",
                        Map.of("confirmed", true),
                        null
                )
        ));

        StreamEvent event = events.stream()
                .filter(item -> "request.submit".equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertThat(event.payload()).containsEntry("requestId", "req_submit_1");
        assertThat(event.payload()).containsEntry("chatId", "chat_1");
        assertThat(event.payload()).containsEntry("runId", "run_1");
        assertThat(event.payload()).containsEntry("toolId", "call_frontend_1");
    }

    @Test
    void shouldEmitArtifactPublishAfterToolResult() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", "chat_1", null, null);
        ArtifactEventPayload artifact = new ArtifactEventPayload(
                "file",
                "report.md",
                "text/markdown",
                12L,
                "/api/resource?file=chat_1%2Fartifacts%2Frun_1%2Freport.md",
                "sha"
        );
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.toolCalls(List.of(new ToolCallDelta(
                        "tool_1",
                        "function",
                        "_artifact_publish_",
                        "{\"path\":\"report.md\"}"
                ))),
                AgentDelta.toolEnd("tool_1"),
                AgentDelta.toolResult("tool_1", "{\"ok\":true}"),
                AgentDelta.artifactPublished(
                        "asset_1",
                        "chat_1",
                        "run_1",
                        artifact
                )
        ));

        int toolEnd = indexOfToolEvent(events, "tool.end", "tool_1");
        int artifactPublish = indexOfEvent(events, "artifact.publish", "artifactId", "asset_1");
        int toolResult = indexOfToolEvent(events, "tool.result", "tool_1");

        assertThat(toolEnd).isGreaterThanOrEqualTo(0);
        assertThat(toolResult).isGreaterThan(toolEnd);
        assertThat(artifactPublish).isGreaterThan(toolResult);
        assertThat(events.get(artifactPublish).payload()).containsEntry("chatId", "chat_1");
        assertThat(events.get(artifactPublish).payload()).containsEntry("runId", "run_1");
    }

    @Test
    void shouldMapConsumedSteerDeltaToRequestSteerEvent() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", "chat_1", null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.requestSteer("req_steer_1", "steer_1", "keep going")
        ));

        StreamEvent event = events.stream()
                .filter(item -> "request.steer".equals(item.type()))
                .findFirst()
                .orElseThrow();
        assertThat(event.payload()).containsEntry("requestId", "req_steer_1");
        assertThat(event.payload()).containsEntry("chatId", "chat_1");
        assertThat(event.payload()).containsEntry("runId", "run_1");
        assertThat(event.payload()).containsEntry("steerId", "steer_1");
        assertThat(event.payload()).containsEntry("message", "keep going");
        assertThat(event.payload()).containsEntry("role", "user");
    }

    @Test
    void shouldIncludeAgentKeyInRunStartBootstrapEvent() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_agent_1",
                        "chat_agent_1",
                        "user",
                        "agent test",
                        "demoModeReact",
                        null,
                        null,
                        null,
                        null,
                        true,
                        "agent-chat",
                        "run_agent_1"
                ));

        StreamEvent runStart = state.bootstrapEvents().stream()
                .filter(event -> "run.start".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(runStart.payload()).containsEntry("agentKey", "demoModeReact");
    }

    @Test
    void shouldIncludeTeamIdInRequestQueryBootstrapEvent() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_team_1",
                        "chat_team_1",
                        "user",
                        "team test",
                        "demoModeReact",
                        "a1b2c3d4e5f6",
                        null,
                        null,
                        null,
                        true,
                        "team-chat",
                        "run_team_1"
                ));

        StreamEvent requestQuery = state.bootstrapEvents().stream()
                .filter(event -> "request.query".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(requestQuery.payload()).containsEntry("teamId", "a1b2c3d4e5f6");
    }

    @Test
    void shouldRejectRunStartWithoutAgentKey() {
        assertThatThrownBy(() -> new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_missing_agent_1",
                        "chat_missing_agent_1",
                        "user",
                        "missing agent",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        "missing-agent-chat",
                        "run_missing_agent_1"
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run.start requires non-blank agentKey")
                .hasMessageContaining("chatId=chat_missing_agent_1")
                .hasMessageContaining("runId=run_missing_agent_1");
    }

    @Test
    void shouldEmitRequestSteerEvent() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
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

        List<StreamEvent> events = state.consume(new StreamInput.RequestSteer(
                "req_steer_1",
                "chat_1",
                "run_1",
                "steer_1",
                "keep going"
        ));

        StreamEvent requestSteer = events.stream()
                .filter(event -> "request.steer".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(requestSteer.payload()).containsEntry("requestId", "req_steer_1");
        assertThat(requestSteer.payload()).containsEntry("chatId", "chat_1");
        assertThat(requestSteer.payload()).containsEntry("runId", "run_1");
        assertThat(requestSteer.payload()).containsEntry("steerId", "steer_1");
        assertThat(requestSteer.payload()).containsEntry("message", "keep going");
        assertThat(requestSteer.payload()).containsEntry("role", "user");
    }

    @Test
    void requestSteerShouldCloseOpenContentBeforeConfirmEvent() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
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

        List<StreamEvent> contentEvents = state.consume(new StreamInput.ContentDelta("content_1", "hello", null));
        List<StreamEvent> steerEvents = state.consume(new StreamInput.RequestSteer(
                "req_steer_1",
                "chat_1",
                "run_1",
                "steer_1",
                "keep going"
        ));

        assertThat(contentEvents).anyMatch(event -> "content.delta".equals(event.type()));
        assertThat(steerEvents).extracting(StreamEvent::type).containsExactly("content.end", "request.steer");
    }

    @Test
    void runErrorShouldCarryStructuredErrorPayload() {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper("run_1", null, null, null);
        List<StreamEvent> events = assembleEvents(mapper, List.of(
                AgentDelta.runError(
                        new AgentDelta.RunError(
                                "run_timeout",
                                "运行超时，本次执行已结束。已运行 301000ms，超过 runTimeoutMs=300000。",
                                "run",
                                "timeout",
                                Map.of("elapsedMs", 301000L, "timeoutMs", 300000L)
                        )
                )
        ));

        StreamEvent runError = events.stream()
                .filter(event -> "run.error".equals(event.type()))
                .findFirst()
                .orElseThrow();

        assertThat(runError.payload()).doesNotContainKey("finishReason");
        assertThat(runError.payload()).containsKey("error");
        assertThat(runError.payload().get("error")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) runError.payload().get("error");
        assertThat(error).containsEntry("code", "run_timeout");
        assertThat(error).containsEntry("scope", "run");
        assertThat(error).containsEntry("category", "timeout");
        assertThat(error).containsKey("diagnostics");
    }

    @Test
    void runCancelShouldTerminateStreamWithoutRunComplete() {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
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

        List<StreamEvent> cancelEvents = state.consume(new StreamInput.RunCancel("run_1"));
        List<StreamEvent> completionEvents = state.complete();

        assertThat(cancelEvents).anyMatch(event -> "run.cancel".equals(event.type()));
        assertThat(completionEvents).isEmpty();
    }

    private List<StreamEvent> assembleEvents(AgentDeltaToStreamInputMapper mapper, List<AgentDelta> deltas) {
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
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
        List<StreamEvent> events = new ArrayList<>(state.bootstrapEvents());
        for (AgentDelta delta : deltas) {
            for (StreamInput input : mapper.mapOrEmpty(delta)) {
                events.addAll(state.consume(input));
            }
        }
        events.addAll(state.complete());
        return events;
    }

    private int indexOfActionEvent(List<StreamEvent> events, String type, String actionId) {
        for (int i = 0; i < events.size(); i++) {
            StreamEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            Object value = event.payload().get("actionId");
            if (actionId.equals(String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfToolEvent(List<StreamEvent> events, String type, String toolId) {
        for (int i = 0; i < events.size(); i++) {
            StreamEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            Object value = event.payload().get("toolId");
            if (toolId.equals(String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }

    private long countActionEvent(List<StreamEvent> events, String type, String actionId) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .filter(event -> actionId.equals(String.valueOf(event.payload().get("actionId"))))
                .count();
    }

    private long countToolEvent(List<StreamEvent> events, String type, String toolId) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .filter(event -> toolId.equals(String.valueOf(event.payload().get("toolId"))))
                .count();
    }

    private List<String> payloadValues(List<StreamEvent> events, String type, String payloadKey) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .map(event -> String.valueOf(event.payload().get(payloadKey)))
                .toList();
    }

    private int indexOfEvent(List<StreamEvent> events, String type, String payloadKey, String payloadValue) {
        for (int i = 0; i < events.size(); i++) {
            StreamEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            Object value = event.payload().get(payloadKey);
            if (payloadValue.equals(String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }
}
