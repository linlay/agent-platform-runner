package com.aiagent.agw.sdk.model;

import java.util.Map;

public sealed interface AgwInput permits
        AgwInput.PlanCreate,
        AgwInput.PlanUpdate,
        AgwInput.TaskStart,
        AgwInput.TaskComplete,
        AgwInput.TaskCancel,
        AgwInput.TaskFail,
        AgwInput.ReasoningDelta,
        AgwInput.ReasoningSnapshot,
        AgwInput.ContentDelta,
        AgwInput.ContentSnapshot,
        AgwInput.ToolArgs,
        AgwInput.ToolEnd,
        AgwInput.ToolResult,
        AgwInput.ToolSnapshot,
        AgwInput.ActionArgs,
        AgwInput.ActionEnd,
        AgwInput.ActionParam,
        AgwInput.ActionResult,
        AgwInput.ActionSnapshot,
        AgwInput.SourceSnapshot,
        AgwInput.RunComplete,
        AgwInput.RunCancel {

    record PlanCreate(String planId, String chatId, Object plan) implements AgwInput {
        public PlanCreate {
            requireNonBlank(planId, "planId");
            requireNonBlank(chatId, "chatId");
            requireNonNull(plan, "plan");
        }
    }

    record PlanUpdate(String planId, Object plan, String chatId) implements AgwInput {
        public PlanUpdate {
            requireNonBlank(planId, "planId");
            requireNonNull(plan, "plan");
        }
    }

    record TaskStart(String taskId, String runId, String taskName, String description) implements AgwInput {
        public TaskStart {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(runId, "runId");
        }
    }

    record TaskComplete(String taskId) implements AgwInput {
        public TaskComplete {
            requireNonBlank(taskId, "taskId");
        }
    }

    record TaskCancel(String taskId) implements AgwInput {
        public TaskCancel {
            requireNonBlank(taskId, "taskId");
        }
    }

    record TaskFail(String taskId, Map<String, Object> error) implements AgwInput {
        public TaskFail {
            requireNonBlank(taskId, "taskId");
            requireNonNull(error, "error");
        }
    }

    record ReasoningDelta(String reasoningId, String delta, String taskId) implements AgwInput {
        public ReasoningDelta {
            requireNonBlank(reasoningId, "reasoningId");
            requireNonNull(delta, "delta");
        }
    }

    record ReasoningSnapshot(String reasoningId, String text, String taskId) implements AgwInput {
        public ReasoningSnapshot {
            requireNonBlank(reasoningId, "reasoningId");
            requireNonNull(text, "text");
        }
    }

    record ContentDelta(String contentId, String delta, String taskId) implements AgwInput {
        public ContentDelta {
            requireNonBlank(contentId, "contentId");
            requireNonNull(delta, "delta");
        }
    }

    record ContentSnapshot(String contentId, String text, String taskId) implements AgwInput {
        public ContentSnapshot {
            requireNonBlank(contentId, "contentId");
            requireNonNull(text, "text");
        }
    }

    record ToolArgs(
            String toolId,
            String delta,
            String taskId,
            String toolName,
            String toolType,
            String toolApi,
            Object toolParams,
            String description,
            Integer chunkIndex
    ) implements AgwInput {
        public ToolArgs {
            requireNonBlank(toolId, "toolId");
            requireNonNull(delta, "delta");
        }
    }

    record ToolEnd(String toolId) implements AgwInput {
        public ToolEnd {
            requireNonBlank(toolId, "toolId");
        }
    }

    record ToolResult(String toolId, String result) implements AgwInput {
        public ToolResult {
            requireNonBlank(toolId, "toolId");
            requireNonNull(result, "result");
        }
    }

    record ToolSnapshot(
            String toolId,
            String toolName,
            String taskId,
            String toolType,
            String toolApi,
            Object toolParams,
            String description,
            String arguments
    ) implements AgwInput {
        public ToolSnapshot {
            requireNonBlank(toolId, "toolId");
        }
    }

    record ActionArgs(String actionId, String delta, String taskId, String actionName, String description) implements AgwInput {
        public ActionArgs {
            requireNonBlank(actionId, "actionId");
            requireNonNull(delta, "delta");
        }
    }

    record ActionEnd(String actionId) implements AgwInput {
        public ActionEnd {
            requireNonBlank(actionId, "actionId");
        }
    }

    record ActionParam(String actionId, Object param) implements AgwInput {
        public ActionParam {
            requireNonBlank(actionId, "actionId");
            requireNonNull(param, "param");
        }
    }

    record ActionResult(String actionId, Object result) implements AgwInput {
        public ActionResult {
            requireNonBlank(actionId, "actionId");
            requireNonNull(result, "result");
        }
    }

    record ActionSnapshot(String actionId, String actionName, String taskId, String description, String arguments) implements AgwInput {
        public ActionSnapshot {
            requireNonBlank(actionId, "actionId");
        }
    }

    record SourceSnapshot(String sourceId, String runId, String taskId, String icon, String title, String url) implements AgwInput {
        public SourceSnapshot {
            requireNonBlank(sourceId, "sourceId");
        }
    }

    record RunComplete(String finishReason) implements AgwInput {
    }

    record RunCancel() implements AgwInput {
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
