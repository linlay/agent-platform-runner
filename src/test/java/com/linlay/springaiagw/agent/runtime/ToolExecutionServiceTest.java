package com.linlay.springaiagw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.agent.AgentDefinition;
import com.linlay.springaiagw.agent.PlannedToolCall;
import com.linlay.springaiagw.agent.ToolArgumentResolver;
import com.linlay.springaiagw.agent.mode.OneshotMode;
import com.linlay.springaiagw.agent.mode.StageSettings;
import com.linlay.springaiagw.agent.runtime.policy.Budget;
import com.linlay.springaiagw.agent.runtime.policy.ComputePolicy;
import com.linlay.springaiagw.agent.runtime.policy.RunSpec;
import com.linlay.springaiagw.agent.runtime.policy.ToolChoice;
import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.model.AgentDelta;
import com.linlay.springaiagw.tool.BaseTool;
import com.linlay.springaiagw.tool.SystemPlanGetTasks;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void planGetShouldReturnCurrentPlanSnapshot() {
        SystemPlanGetTasks planGetTool = new SystemPlanGetTasks();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(planGetTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null
        );

        ExecutionContext context = new ExecutionContext(
                definition(),
                new AgentRequest("test", "chat_plan_1", null, "run_plan_1"),
                List.of()
        );
        context.initializePlan(
                "plan_chat_1",
                List.of(
                        new AgentDelta.PlanTask("task1", "检查环境", "init"),
                        new AgentDelta.PlanTask("task2", "执行迁移", "in_progress")
                )
        );

        Map<String, BaseTool> enabledTools = new LinkedHashMap<>();
        for (BaseTool tool : toolRegistry.list()) {
            enabledTools.put(tool.name(), tool);
        }

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("_plan_get_tasks_", Map.of(), "call_plan_get_tasks_1")),
                Map.copyOf(enabledTools),
                new ArrayList<>(),
                "run_plan_1",
                context,
                false
        );

        assertThat(batch.events()).hasSize(1);
        String result = batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .filter(item -> "call_plan_get_tasks_1".equals(item.toolId()))
                .map(AgentDelta.ToolResult::result)
                .findFirst()
                .orElseThrow();

        assertThat(result)
                .contains("计划ID: plan_chat_1")
                .contains("task1 | init | 检查环境")
                .contains("task2 | init | 执行迁移")
                .contains("当前应执行 taskId: task1");
    }

    @Test
    void backendRuntimeFailureShouldRetryByToolBudget() throws Exception {
        FlakyTool flakyTool = new FlakyTool("flaky_tool", 2, "OK");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(flakyTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 500L, 2)
        );
        ExecutionContext context = new ExecutionContext(
                definition(List.of("flaky_tool"), budget),
                new AgentRequest("test", "chat_retry_1", null, "run_retry_1"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("flaky_tool", Map.of(), "call_retry_1")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_1",
                context,
                false
        );

        assertThat(flakyTool.attempts()).isEqualTo(3);
        assertThat(singleToolResult(batch, "call_retry_1")).isEqualTo("OK");
    }

    @Test
    void backendIllegalArgumentShouldNotRetry() throws Exception {
        FlakyIllegalArgumentTool badArgsTool = new FlakyIllegalArgumentTool("bad_args_tool");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(badArgsTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 500L, 3)
        );
        ExecutionContext context = new ExecutionContext(
                definition(List.of("bad_args_tool"), budget),
                new AgentRequest("test", "chat_retry_2", null, "run_retry_2"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("bad_args_tool", Map.of(), "call_retry_2")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_2",
                context,
                false
        );

        assertThat(badArgsTool.attempts()).isEqualTo(1);
        String result = singleToolResult(batch, "call_retry_2");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("invalid arguments");
    }

    @Test
    void backendTimeoutShouldRetryByToolBudget() throws Exception {
        SlowTool slowTool = new SlowTool("slow_tool", 80L);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(slowTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 20L, 2)
        );
        ExecutionContext context = new ExecutionContext(
                definition(List.of("slow_tool"), budget),
                new AgentRequest("test", "chat_retry_3", null, "run_retry_3"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("slow_tool", Map.of(), "call_retry_3")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_3",
                context,
                false
        );

        assertThat(slowTool.attempts()).isEqualTo(3);
        String result = singleToolResult(batch, "call_retry_3");
        assertThat(result).contains("Backend tool timeout");
        assertThat(result).contains("\"ok\":false");
    }

    private Map<String, BaseTool> enabledTools(ToolRegistry toolRegistry) {
        Map<String, BaseTool> enabledTools = new LinkedHashMap<>();
        for (BaseTool tool : toolRegistry.list()) {
            enabledTools.put(tool.name(), tool);
        }
        return Map.copyOf(enabledTools);
    }

    private String singleToolResult(ToolExecutionService.ToolExecutionBatch batch, String callId) {
        return batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .filter(item -> callId.equals(item.toolId()))
                .map(AgentDelta.ToolResult::result)
                .findFirst()
                .orElseThrow();
    }

    private AgentDefinition definition() {
        return definition(List.of("_plan_get_tasks_"), Budget.DEFAULT);
    }

    private AgentDefinition definition(List<String> tools, Budget budget) {
        return new AgentDefinition(
                "runtime_test",
                "runtime_test",
                null,
                "runtime test",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, budget),
                new OneshotMode(new StageSettings("sys", null, null, tools, false, ComputePolicy.MEDIUM), null, null),
                tools,
                List.of()
        );
    }

    private static final class FlakyTool implements BaseTool {
        private final String name;
        private final int failuresBeforeSuccess;
        private final String successValue;
        private final AtomicInteger attempts = new AtomicInteger();

        private FlakyTool(String name, int failuresBeforeSuccess, String successValue) {
            this.name = name;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.successValue = successValue;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            int current = attempts.incrementAndGet();
            if (current <= failuresBeforeSuccess) {
                throw new RuntimeException("temporary failure " + current);
            }
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(successValue);
        }

        private int attempts() {
            return attempts.get();
        }
    }

    private static final class FlakyIllegalArgumentTool implements BaseTool {
        private final String name;
        private final AtomicInteger attempts = new AtomicInteger();

        private FlakyIllegalArgumentTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("invalid arguments");
        }

        private int attempts() {
            return attempts.get();
        }
    }

    private static final class SlowTool implements BaseTool {
        private final String name;
        private final long sleepMs;
        private final AtomicInteger attempts = new AtomicInteger();

        private SlowTool(String name, long sleepMs) {
            this.name = name;
            this.sleepMs = sleepMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            attempts.incrementAndGet();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                // cancelled by timeout policy, ignore and let this invocation end
            }
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("slow-ok");
        }

        private int attempts() {
            return attempts.get();
        }
    }
}
