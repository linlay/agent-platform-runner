package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.agent.PlanToolConstants;
import com.linlay.agentplatform.model.AgentDelta;

import java.util.List;
import java.util.Map;

public class PlanToolHandler {

    private final PlanTaskDeltaBuilder planTaskDeltaBuilder;

    public PlanToolHandler(PlanTaskDeltaBuilder planTaskDeltaBuilder) {
        this.planTaskDeltaBuilder = planTaskDeltaBuilder;
    }

    public boolean handles(String toolName) {
        return PlanToolConstants.PLAN_GET_TASKS_TOOL.equals(toolName);
    }

    public JsonNode invoke(String toolName, ExecutionContext context) {
        if (!handles(toolName)) {
            return null;
        }
        return planTaskDeltaBuilder.planGetResult(planState(context));
    }

    public ToolExecutionService.PlanState planState(ExecutionContext context) {
        if (context == null) {
            return new ToolExecutionService.PlanState("plan_default", null, List.of());
        }
        String planId = context.planId();
        String chatId = context.request() == null ? null : context.request().chatId();
        return new ToolExecutionService.PlanState(planId, chatId, context.planTasks());
    }

    public AgentDelta planUpdateDelta(ExecutionContext context, String toolName, Map<String, Object> args, JsonNode resultNode) {
        return planTaskDeltaBuilder.planUpdateDelta(context, toolName, args, resultNode);
    }
}
