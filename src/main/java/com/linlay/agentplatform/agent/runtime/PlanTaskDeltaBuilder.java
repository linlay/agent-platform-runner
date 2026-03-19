package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.PlanToolConstants;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.util.MapReaders;
import com.linlay.agentplatform.util.StringHelpers;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracted plan state/result helpers from ToolExecutionService.
 */
final class PlanTaskDeltaBuilder {

    private final ObjectMapper objectMapper;

    PlanTaskDeltaBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode planGetResult(ToolExecutionService.PlanState state) {
        return objectMapper.getNodeFactory().textNode(planStateText(state));
    }

    AgentDelta planUpdateDelta(
            ExecutionContext context,
            String toolName,
            Map<String, Object> args,
            JsonNode resultNode
    ) {
        if (context == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalizedName = toolName.trim().toLowerCase(Locale.ROOT);
        if (PlanToolConstants.PLAN_ADD_TASKS_TOOL.equals(normalizedName)) {
            String resultText = toResultText(resultNode);
            if (isFailedPlanToolResult(resultText)) {
                return null;
            }
            List<AgentDelta.PlanTask> created = parsePlanTasksFromResult(resultText);
            if (created.isEmpty()) {
                return null;
            }
            context.appendPlanTasks(created);
            return AgentDelta.planUpdate(context.planId(), context.request().chatId(), context.planTasks());
        }
        if (PlanToolConstants.PLAN_UPDATE_TASK_TOOL.equals(normalizedName)) {
            String resultText = toResultText(resultNode);
            if (!isSuccessfulPlanUpdateResult(resultText, resultNode)) {
                return null;
            }
            String taskId = asString(args, "taskId");
            String status = asString(args, "status");
            if (!StringUtils.hasText(taskId) || !StringUtils.hasText(status)) {
                return null;
            }
            String description = asString(args, "description");
            boolean updated = context.updatePlanTask(taskId, status, description);
            if (!updated) {
                return null;
            }
            return AgentDelta.planUpdate(context.planId(), context.request().chatId(), context.planTasks());
        }
        return null;
    }

    String planStateText(ToolExecutionService.PlanState state) {
        StringBuilder text = new StringBuilder();
        text.append("计划ID: ").append(normalize(state.planId())).append('\n');
        text.append("任务列表:");
        if (state.tasks().isEmpty()) {
            text.append("\n- (空)");
        } else {
            for (AgentDelta.PlanTask task : state.tasks()) {
                if (task == null) {
                    continue;
                }
                text.append("\n- ")
                        .append(normalize(task.taskId()))
                        .append(" | ")
                        .append(normalizeStatus(task.status()))
                        .append(" | ")
                        .append(normalize(task.description()));
            }
        }
        text.append('\n')
                .append("当前应执行 taskId: ")
                .append(firstUnfinishedTaskId(state.tasks()));
        return text.toString();
    }

    String firstUnfinishedTaskId(List<AgentDelta.PlanTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "none";
        }
        for (AgentDelta.PlanTask task : tasks) {
            if (task == null || !StringUtils.hasText(task.taskId())) {
                continue;
            }
            String status = normalizeStatus(task.status());
            if (!"completed".equals(status) && !"canceled".equals(status) && !"failed".equals(status)) {
                return task.taskId().trim();
            }
        }
        return "none";
    }

    List<AgentDelta.PlanTask> parsePlanTasksFromResult(String resultText) {
        if (!StringUtils.hasText(resultText)) {
            return List.of();
        }
        String normalized = resultText.replace("\r\n", "\n");
        List<AgentDelta.PlanTask> tasks = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            String taskId = normalize(parts[0]);
            String status = parseLineStatus(parts[1]);
            String description = normalize(parts[2]);
            if (!StringUtils.hasText(taskId) || !StringUtils.hasText(description) || !StringUtils.hasText(status)) {
                continue;
            }
            tasks.add(new AgentDelta.PlanTask(taskId, description, status));
        }
        return tasks.isEmpty() ? List.of() : List.copyOf(tasks);
    }

    private String asString(Map<String, Object> args, String key) {
        return MapReaders.readString(args, key);
    }

    private String toResultText(JsonNode result) {
        if (result == null || result.isNull()) {
            return "null";
        }
        if (result.isTextual()) {
            return result.asText();
        }
        return result.toString();
    }

    private boolean isFailedPlanToolResult(String resultText) {
        if (!StringUtils.hasText(resultText)) {
            return true;
        }
        return normalize(resultText).startsWith("失败:");
    }

    private boolean isSuccessfulPlanUpdateResult(String resultText, JsonNode resultNode) {
        if ("OK".equals(normalize(resultText))) {
            return true;
        }
        if (resultNode == null || resultNode.isNull()) {
            return false;
        }
        if (resultNode.isObject() && resultNode.has("ok")) {
            return resultNode.path("ok").asBoolean(false);
        }
        return false;
    }

    private String parseLineStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "init";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "init", "completed", "failed", "canceled" -> normalized;
            default -> null;
        };
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }
}
