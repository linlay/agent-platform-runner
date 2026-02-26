package com.linlay.agentplatform.stream.model;

import java.util.Map;

public sealed interface StreamInput permits
        StreamInput.PlanUpdate,
        StreamInput.TaskStart,
        StreamInput.TaskComplete,
        StreamInput.TaskCancel,
        StreamInput.TaskFail,
        StreamInput.ReasoningDelta,
        StreamInput.ContentDelta,
        StreamInput.ToolArgs,
        StreamInput.ToolEnd,
        StreamInput.ToolResult,
        StreamInput.ActionArgs,
        StreamInput.ActionEnd,
        StreamInput.ActionResult,
        StreamInput.RequestSubmit,
        StreamInput.RunComplete {

    record PlanUpdate(String planId, Object plan, String chatId) implements StreamInput {
        public PlanUpdate {
            requireNonBlank(planId, "planId");
            requireNonNull(plan, "plan");
        }
    }

    record TaskStart(String taskId, String runId, String taskName, String description) implements StreamInput {
        public TaskStart {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(runId, "runId");
        }
    }

    record TaskComplete(String taskId) implements StreamInput {
        public TaskComplete {
            requireNonBlank(taskId, "taskId");
        }
    }

    record TaskCancel(String taskId) implements StreamInput {
        public TaskCancel {
            requireNonBlank(taskId, "taskId");
        }
    }

    record TaskFail(String taskId, Map<String, Object> error) implements StreamInput {
        public TaskFail {
            requireNonBlank(taskId, "taskId");
            requireNonNull(error, "error");
        }
    }

    record ReasoningDelta(String reasoningId, String delta, String taskId) implements StreamInput {
        public ReasoningDelta {
            requireNonBlank(reasoningId, "reasoningId");
            requireNonNull(delta, "delta");
        }
    }

    record ContentDelta(String contentId, String delta, String taskId) implements StreamInput {
        public ContentDelta {
            requireNonBlank(contentId, "contentId");
            requireNonNull(delta, "delta");
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
    ) implements StreamInput {
        public ToolArgs {
            requireNonBlank(toolId, "toolId");
            requireNonNull(delta, "delta");
        }
    }

    record ToolEnd(String toolId) implements StreamInput {
        public ToolEnd {
            requireNonBlank(toolId, "toolId");
        }
    }

    record ToolResult(String toolId, String result) implements StreamInput {
        public ToolResult {
            requireNonBlank(toolId, "toolId");
            requireNonNull(result, "result");
        }
    }

    record ActionArgs(String actionId, String delta, String taskId, String actionName, String description) implements StreamInput {
        public ActionArgs {
            requireNonBlank(actionId, "actionId");
            requireNonNull(delta, "delta");
        }
    }

    record ActionEnd(String actionId) implements StreamInput {
        public ActionEnd {
            requireNonBlank(actionId, "actionId");
        }
    }

    record ActionResult(String actionId, Object result) implements StreamInput {
        public ActionResult {
            requireNonBlank(actionId, "actionId");
            requireNonNull(result, "result");
        }
    }

    record RequestSubmit(
            String requestId,
            String chatId,
            String runId,
            String toolId,
            Object payload,
            String viewId
    ) implements StreamInput {
        public RequestSubmit {
            requireNonBlank(requestId, "requestId");
            requireNonBlank(chatId, "chatId");
            requireNonBlank(runId, "runId");
            requireNonBlank(toolId, "toolId");
            requireNonNull(payload, "payload");
        }
    }

    record RunComplete(String finishReason) implements StreamInput {
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
