package com.linlay.agentplatform.agent.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.FatalToolExecutionException;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitTimeoutException;
import com.linlay.agentplatform.agent.runtime.ToolExecutionService;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestratorServicesTest {

    @Test
    void retryCountsShouldUseBudgetModelAndToolSettings() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 10_000L, 3),
                new Budget.Scope(10, 20_000L, 4)
        );
        ExecutionContext context = contextWithBudget(budget);
        OrchestratorServices services = new OrchestratorServices(null, null, new ObjectMapper());

        assertThat(services.modelRetryCount(context, 1)).isEqualTo(3);
        assertThat(services.toolRetryCount(context, 1)).isEqualTo(4);
    }

    @Test
    void retryCountsShouldFallbackWhenConfiguredRetryIsZero() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(10, 20_000L, 0)
        );
        ExecutionContext context = contextWithBudget(budget);
        OrchestratorServices services = new OrchestratorServices(null, null, new ObjectMapper());

        assertThat(services.modelRetryCount(context, 2)).isEqualTo(2);
        assertThat(services.toolRetryCount(context, 2)).isEqualTo(2);
    }

    @Test
    void executeToolsAndEmitShouldEmitDeltasThenThrowFrontendTimeoutWithoutBudgetIncrement() {
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        OrchestratorServices services = new OrchestratorServices(mock(LlmService.class), toolExecutionService, new ObjectMapper());
        ExecutionContext context = contextWithBudget(new Budget(
                0L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(10, 20_000L, 0)
        ));

        String timeoutResult = """
                {"tool":"confirm_dialog","ok":false,"code":"frontend_submit_timeout","error":"Frontend tool submit timeout runId=run_1, toolId=call_frontend_1"}
                """.trim();
        ToolExecutionService.ToolExecutionBatch batch = new ToolExecutionService.ToolExecutionBatch(
                List.of(AgentDelta.toolResult("call_frontend_1", timeoutResult)),
                        List.of(new ToolExecutionService.ToolExecutionEvent(
                                "call_frontend_1",
                                "confirm_dialog",
                                "html",
                                "{\"question\":\"去哪玩\"}",
                                timeoutResult
                        ))
        );
        when(toolExecutionService.executeToolCalls(
                anyList(),
                anyMap(),
                anyList(),
                eq("run_1"),
                any(ExecutionContext.class),
                eq(false),
                any(),
                any()
        )).thenReturn(batch);

        List<AgentDelta> emitted = new ArrayList<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Flux<AgentDelta> flux = Flux.create(sink -> {
            try {
                services.executeToolsAndEmit(context, Map.of(), List.of(), sink);
            } catch (Throwable ex) {
                thrown.set(ex);
            } finally {
                sink.complete();
            }
        });
        emitted.addAll(flux.collectList().block(Duration.ofSeconds(3)));

        assertThat(thrown.get()).isInstanceOf(FrontendSubmitTimeoutException.class);
        assertThat(thrown.get().getMessage()).contains("Frontend tool submit timeout");
        assertThat(emitted.stream()
                .flatMap(delta -> delta.toolResults().stream())
                .map(AgentDelta.ToolResult::toolId)
                .toList()).contains("call_frontend_1");
    }

    @Test
    void executeToolsAndEmitShouldThrowFatalToolExecutionExceptionWhenToolIsUnregistered() {
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        OrchestratorServices services = new OrchestratorServices(mock(LlmService.class), toolExecutionService, new ObjectMapper());
        ExecutionContext context = contextWithBudget(new Budget(
                0L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(10, 20_000L, 0)
        ));

        String fatalResult = """
                {"tool":"missing_tool","ok":false,"code":"tool_not_registered","error":"Tool is not registered: missing_tool"}
                """.trim();
        ToolExecutionService.ToolExecutionBatch batch = new ToolExecutionService.ToolExecutionBatch(
                List.of(AgentDelta.toolResult("call_missing_1", fatalResult)),
                List.of(new ToolExecutionService.ToolExecutionEvent(
                        "call_missing_1",
                        "missing_tool",
                        "function",
                        "{}",
                        fatalResult
                ))
        );
        when(toolExecutionService.executeToolCalls(
                anyList(),
                anyMap(),
                anyList(),
                eq("run_1"),
                any(ExecutionContext.class),
                eq(false),
                any(),
                any()
        )).thenReturn(batch);

        List<AgentDelta> emitted = new ArrayList<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Flux<AgentDelta> flux = Flux.create(sink -> {
            try {
                services.executeToolsAndEmit(context, Map.of(), List.of(), sink);
            } catch (Throwable ex) {
                thrown.set(ex);
            } finally {
                sink.complete();
            }
        });
        emitted.addAll(flux.collectList().block(Duration.ofSeconds(3)));

        assertThat(thrown.get()).isInstanceOf(FatalToolExecutionException.class);
        assertThat(thrown.get().getMessage()).contains("Tool is not registered");
        assertThat(emitted.stream()
                .flatMap(delta -> delta.toolResults().stream())
                .map(AgentDelta.ToolResult::toolId)
                .toList()).contains("call_missing_1");
    }

    @Test
    void callModelTurnStreamingShouldEmitToolEndImmediatelyAfterLastArgs() {
        LlmService llmService = mock(LlmService.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        OrchestratorServices services = new OrchestratorServices(llmService, toolExecutionService, new ObjectMapper());
        ExecutionContext context = contextWithBudget(Budget.DEFAULT);

        when(llmService.streamDeltas(any())).thenReturn(Flux.just(
                new LlmDelta(
                        null,
                        null,
                        List.of(new ToolCallDelta("action_1", "action", "show_modal", "{\"title\":\"马")),
                        null
                ),
                new LlmDelta(
                        null,
                        null,
                        List.of(new ToolCallDelta("action_1", null, null, "年诗\"}")),
                        null
                ),
                new LlmDelta(
                        null,
                        null,
                        List.of(new ToolCallDelta("action_2", "action", "launch_fireworks", "{\"durationMs\":5000}")),
                        null
                ),
                new LlmDelta(
                        null,
                        null,
                        List.of(new ToolCallDelta("action_3", "action", "switch_theme", "{\"theme\":\"dark\"}")),
                        "tool_calls"
                )
        ));

        List<AgentDelta> emitted = Flux.<AgentDelta>create(sink -> {
            services.callModelTurnStreaming(
                    context,
                    new StageSettings("sys", null, null, List.of(), false, ComputePolicy.MEDIUM),
                    List.of(),
                    null,
                    Map.of(),
                    List.of(),
                    ToolChoice.AUTO,
                    "test-stage",
                    false,
                    false,
                    false,
                    true,
                    sink
            );
            sink.complete();
        }).collectList().block(Duration.ofSeconds(3));

        assertThat(emitted).isNotNull();
        List<String> sequence = emitted.stream()
                .flatMap(delta -> {
                    List<String> items = new ArrayList<>();
                    delta.toolCalls().forEach(call -> items.add("args:" + call.id()));
                    delta.toolEnds().forEach(toolId -> items.add("end:" + toolId));
                    return items.stream();
                })
                .toList();

        assertThat(sequence).containsExactly(
                "args:action_1",
                "args:action_1",
                "end:action_1",
                "args:action_2",
                "end:action_2",
                "args:action_3",
                "end:action_3"
        );
    }

    private ExecutionContext contextWithBudget(Budget budget) {
        AgentDefinition definition = new AgentDefinition(
                "orchestrator_test",
                "orchestrator_test",
                null,
                "orchestrator test",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, budget),
                new OneshotMode(
                        new StageSettings("sys", null, null, List.of(), false, ComputePolicy.MEDIUM),
                        null,
                        null
                ),
                List.of(),
                List.of()
        );
        AgentRequest request = new AgentRequest("hello", "chat_1", "req_1", "run_1");
        return new ExecutionContext(definition, request, List.of());
    }
}
