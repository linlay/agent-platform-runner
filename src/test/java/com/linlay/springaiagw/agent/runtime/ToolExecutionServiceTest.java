package com.linlay.springaiagw.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.agent.AgentDefinition;
import com.linlay.springaiagw.agent.PlannedToolCall;
import com.linlay.springaiagw.agent.ToolArgumentResolver;
import com.linlay.springaiagw.agent.mode.OneshotMode;
import com.linlay.springaiagw.agent.mode.StageSettings;
import com.linlay.springaiagw.agent.runtime.AgentRuntimeMode;
import com.linlay.springaiagw.agent.runtime.policy.Budget;
import com.linlay.springaiagw.agent.runtime.policy.ComputePolicy;
import com.linlay.springaiagw.agent.runtime.policy.ControlStrategy;
import com.linlay.springaiagw.agent.runtime.policy.OutputPolicy;
import com.linlay.springaiagw.agent.runtime.policy.RunSpec;
import com.linlay.springaiagw.agent.runtime.policy.ToolPolicy;
import com.linlay.springaiagw.agent.runtime.policy.VerifyPolicy;
import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.model.stream.AgentDelta;
import com.linlay.springaiagw.tool.BaseTool;
import com.linlay.springaiagw.tool.PlanGetTool;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void planGetShouldReturnCurrentPlanSnapshot() throws Exception {
        PlanGetTool planGetTool = new PlanGetTool();
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
                List.of(new PlannedToolCall("_plan_get_", Map.of(), "call_plan_get_1")),
                Map.copyOf(enabledTools),
                new ArrayList<>(),
                "run_plan_1",
                context,
                false
        );

        assertThat(batch.events()).hasSize(1);
        String result = batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .filter(item -> "call_plan_get_1".equals(item.toolId()))
                .map(AgentDelta.ToolResult::result)
                .findFirst()
                .orElseThrow();

        JsonNode payload = objectMapper.readTree(result);
        assertThat(payload.path("tool").asText()).isEqualTo("_plan_get_");
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.path("planId").asText()).isEqualTo("plan_chat_1");
        assertThat(payload.path("chatId").asText()).isEqualTo("chat_plan_1");
        assertThat(payload.path("tasks")).isNotNull();
        assertThat(payload.path("tasks").size()).isEqualTo(2);
        assertThat(payload.path("tasks").get(0).path("taskId").asText()).isEqualTo("task1");
        assertThat(payload.path("tasks").get(0).path("status").asText()).isEqualTo("init");
    }

    private AgentDefinition definition() {
        return new AgentDefinition(
                "runtime_test",
                "runtime_test",
                null,
                "runtime test",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ControlStrategy.ONESHOT, OutputPolicy.PLAIN, ToolPolicy.ALLOW, VerifyPolicy.NONE, Budget.DEFAULT),
                new OneshotMode(new StageSettings("sys", null, null, List.of("_plan_get_"), false, ComputePolicy.MEDIUM)),
                List.of("_plan_get_")
        );
    }
}
