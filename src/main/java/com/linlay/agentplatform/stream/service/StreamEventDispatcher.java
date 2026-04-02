package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.RunActor;
import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StreamEventDispatcher {

    private final RequestContext context;
    private final StreamEventStateData state;
    private final EventFactory eventFactory;

    StreamEventDispatcher(
            StreamRequest request,
            String chatId,
            String runId,
            RunActor requestActor,
            StreamEventStateData state,
            EventFactory eventFactory
    ) {
        this.context = new RequestContext(request, chatId, runId, requestActor);
        this.state = state;
        this.eventFactory = eventFactory;
    }

    boolean hasRunContext() {
        return context.runId() != null;
    }

    void dispatch(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (handlePlanAndTaskInputs(events, input, actor)) {
            return;
        }
        if (handleTextInputs(events, input, actor)) {
            return;
        }
        if (handleToolInputs(events, input, actor)) {
            return;
        }
        if (handleArtifactInputs(events, input, actor)) {
            return;
        }
        if (handleActionInputs(events, input, actor)) {
            return;
        }
        if (handleRequestInputs(events, input, actor)) {
            return;
        }
        if (handleRunInputs(events, input, actor)) {
            return;
        }
        throw new IllegalStateException("Unknown input type: " + input.getClass().getName());
    }

    void complete(List<StreamEvent> events, String finishReason, RunActor actor) {
        if (state.terminated()) {
            return;
        }
        closeOpenBlocks(events);
        if (state.activeTaskId() != null) {
            emitTaskComplete(events, state.activeTaskId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", context.runId());
        payload.put("finishReason", StreamEventSupport.defaultIfBlank(finishReason, "end_turn"));
        StreamEventSupport.putActor(payload, actor == null ? context.requestActor() : actor);
        events.add(next("run.complete", payload));
        state.setTerminated(true);
    }

    void fail(List<StreamEvent> events, Throwable ex, RunActor actor) {
        if (state.terminated()) {
            return;
        }
        closeOpenBlocks(events);
        emitRunError(events, StreamEventSupport.errorPayload(ex), actor);
    }

    private boolean handlePlanAndTaskInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.PlanUpdate value) {
            ensureChatContext();
            if (value.chatId() != null && !context.chatId().equals(value.chatId())) {
                throw new IllegalStateException("plan.update chatId does not match stream chatId");
            }
            if (state.planId() != null && !state.planId().equals(value.planId())) {
                throw new IllegalStateException("plan.update planId does not match active planId");
            }
            state.setPlanId(value.planId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", value.planId());
            payload.put("plan", value.plan());
            StreamEventSupport.putIfNonNull(payload, "chatId", value.chatId() != null ? value.chatId() : context.chatId());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("plan.update", payload));
            return true;
        }
        if (input instanceof StreamInput.TaskStart value) {
            ensureRunContext();
            if (state.planId() == null) {
                throw new IllegalStateException("task.start requires an active plan");
            }
            if (!context.runId().equals(value.runId())) {
                throw new IllegalStateException("task.start runId does not match stream runId");
            }
            if (state.activeTaskId() != null) {
                throw new IllegalStateException("task.start is not allowed while another task is active");
            }
            emitTaskStart(events, value.taskId(), value.taskName(), value.description());
            return true;
        }
        if (input instanceof StreamInput.TaskComplete value) {
            ensureRunContext();
            ensureActiveTask(value.taskId(), "task.complete");
            closeOpenBlocks(events);
            emitTaskComplete(events, value.taskId());
            return true;
        }
        if (input instanceof StreamInput.TaskCancel value) {
            ensureRunContext();
            ensureActiveTask(value.taskId(), "task.cancel");
            closeOpenBlocks(events);
            emitTaskCancel(events, value.taskId());
            return true;
        }
        if (input instanceof StreamInput.TaskFail value) {
            ensureRunContext();
            ensureActiveTask(value.taskId(), "task.fail");
            closeOpenBlocks(events);
            emitTaskFail(events, value.taskId(), value.error());
            return true;
        }
        return false;
    }

    private boolean handleTextInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.ReasoningDelta value) {
            ensureRunContext();
            String taskId = resolveTaskContext(value.taskId(), "reasoning.delta");
            closeContent(events);
            ensureReasoning(events, value.reasoningId(), taskId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reasoningId", value.reasoningId());
            payload.put("delta", value.delta());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("reasoning.delta", payload));
            return true;
        }
        if (input instanceof StreamInput.ContentDelta value) {
            ensureRunContext();
            String taskId = resolveTaskContext(value.taskId(), "content.delta");
            closeReasoning(events);
            ensureContent(events, value.contentId(), taskId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contentId", value.contentId());
            payload.put("delta", value.delta());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("content.delta", payload));
            return true;
        }
        return false;
    }

    private boolean handleToolInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.ToolArgs value) {
            ensureRunContext();
            String taskId = resolveTaskContext(value.taskId(), "tool.args");
            closeTextBlocks(events);
            if (!state.hasKnownTool(value.toolId())) {
                state.rememberOpenTool(value.toolId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolId", value.toolId());
                payload.put("runId", context.runId());
                StreamEventSupport.putIfNonNull(payload, "taskId", taskId);
                StreamEventSupport.putIfNonNull(payload, "toolName", value.toolName());
                if (StreamEventSupport.shouldEmitToolType(value.toolType())) {
                    StreamEventSupport.putIfNonNull(payload, "toolType", value.toolType());
                }
                StreamEventSupport.putIfNonNull(payload, "toolLabel", value.toolLabel());
                StreamEventSupport.putIfNonNull(payload, "toolDescription", value.toolDescription());
                StreamEventSupport.putActor(payload, actor);
                events.add(next("tool.start", payload));
            } else if (!state.isToolOpen(value.toolId())) {
                throw new IllegalStateException("tool.args for closed toolId: " + value.toolId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", value.toolId());
            payload.put("delta", value.delta());
            payload.put("chunkIndex", value.chunkIndex() != null
                    ? value.chunkIndex()
                    : state.nextToolArgChunkIndex(value.toolId()));
            StreamEventSupport.putActor(payload, actor);
            events.add(next("tool.args", payload));
            return true;
        }
        if (input instanceof StreamInput.ToolEnd value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!state.closeTool(value.toolId())) {
                throw new IllegalStateException("tool.end for unknown or already closed toolId: " + value.toolId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", value.toolId());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("tool.end", payload));
            return true;
        }
        if (input instanceof StreamInput.ToolResult value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!state.hasKnownTool(value.toolId())) {
                throw new IllegalStateException("tool.result references unknown toolId: " + value.toolId());
            }
            if (state.closeTool(value.toolId())) {
                Map<String, Object> endPayload = new LinkedHashMap<>();
                endPayload.put("toolId", value.toolId());
                events.add(next("tool.end", endPayload));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", value.toolId());
            payload.put("result", value.result());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("tool.result", payload));
            return true;
        }
        return false;
    }

    private boolean handleActionInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.ActionArgs value) {
            ensureRunContext();
            String taskId = resolveTaskContext(value.taskId(), "action.args");
            closeTextBlocks(events);
            if (!state.hasKnownAction(value.actionId())) {
                state.rememberOpenAction(value.actionId());
                Map<String, Object> startPayload = new LinkedHashMap<>();
                startPayload.put("actionId", value.actionId());
                startPayload.put("runId", context.runId());
                StreamEventSupport.putIfNonNull(startPayload, "taskId", taskId);
                StreamEventSupport.putIfNonNull(startPayload, "actionName", value.actionName());
                StreamEventSupport.putIfNonNull(startPayload, "description", value.description());
                StreamEventSupport.putActor(startPayload, actor);
                events.add(next("action.start", startPayload));
            } else if (!state.isActionOpen(value.actionId())) {
                throw new IllegalStateException("action.args for closed actionId: " + value.actionId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", value.actionId());
            payload.put("delta", value.delta());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("action.args", payload));
            return true;
        }
        if (input instanceof StreamInput.ActionEnd value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!state.closeAction(value.actionId())) {
                throw new IllegalStateException("action.end for unknown or already closed actionId: " + value.actionId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", value.actionId());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("action.end", payload));
            return true;
        }
        if (input instanceof StreamInput.ActionResult value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!state.hasKnownAction(value.actionId())) {
                throw new IllegalStateException("action.result references unknown actionId: " + value.actionId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", value.actionId());
            payload.put("result", value.result());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("action.result", payload));
            return true;
        }
        return false;
    }

    private boolean handleArtifactInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.ArtifactPublish value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!context.chatId().equals(value.chatId())) {
                throw new IllegalStateException("artifact.publish chatId does not match stream chatId");
            }
            if (!context.runId().equals(value.runId())) {
                throw new IllegalStateException("artifact.publish runId does not match stream runId");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactId", value.artifactId());
            payload.put("chatId", value.chatId());
            payload.put("runId", value.runId());
            payload.put("artifact", value.artifact());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("artifact.publish", payload));
            return true;
        }
        return false;
    }

    private boolean handleRequestInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.RequestSubmit value) {
            ensureRunContext();
            if (!context.chatId().equals(value.chatId())) {
                throw new IllegalStateException("request.submit chatId does not match stream chatId");
            }
            if (!context.runId().equals(value.runId())) {
                throw new IllegalStateException("request.submit runId does not match stream runId");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", value.requestId());
            payload.put("chatId", value.chatId());
            payload.put("runId", value.runId());
            payload.put("toolId", value.toolId());
            payload.put("payload", value.payload());
            StreamEventSupport.putIfNonNull(payload, "viewId", value.viewId());
            StreamEventSupport.putActor(payload, actor);
            events.add(next("request.submit", payload));
            return true;
        }
        if (input instanceof StreamInput.RequestSteer value) {
            ensureRunContext();
            closeTextBlocks(events);
            if (!context.chatId().equals(value.chatId())) {
                throw new IllegalStateException("request.steer chatId does not match stream chatId");
            }
            if (!context.runId().equals(value.runId())) {
                throw new IllegalStateException("request.steer runId does not match stream runId");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            StreamEventSupport.putIfNonNull(payload, "requestId", value.requestId());
            payload.put("chatId", value.chatId());
            payload.put("runId", value.runId());
            payload.put("steerId", value.steerId());
            payload.put("message", value.message());
            payload.put("role", "user");
            StreamEventSupport.putActor(payload, actor);
            events.add(next("request.steer", payload));
            return true;
        }
        return false;
    }

    private boolean handleRunInputs(List<StreamEvent> events, StreamInput input, RunActor actor) {
        if (input instanceof StreamInput.RunCancel value) {
            ensureRunContext();
            if (!context.runId().equals(value.runId())) {
                throw new IllegalStateException("run.cancel runId does not match stream runId");
            }
            closeOpenBlocks(events);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", value.runId());
            StreamEventSupport.putActor(payload, actor == null ? context.requestActor() : actor);
            events.add(next("run.cancel", payload));
            state.setTerminated(true);
            return true;
        }
        if (input instanceof StreamInput.RunComplete value) {
            ensureRunContext();
            complete(events, value.finishReason(), actor);
            return true;
        }
        if (input instanceof StreamInput.RunError value) {
            ensureRunContext();
            errorRun(events, value.error(), actor);
            return true;
        }
        return false;
    }

    private String resolveTaskContext(String requestedTaskId, String eventType) {
        ensureRunContext();
        if (requestedTaskId != null && requestedTaskId.isBlank()) {
            throw new IllegalStateException("taskId must not be blank");
        }
        if (requestedTaskId == null) {
            return state.activeTaskId();
        }
        if (state.activeTaskId() == null) {
            throw new IllegalStateException(eventType + " taskId requires an active task");
        }
        if (!state.activeTaskId().equals(requestedTaskId)) {
            throw new IllegalStateException(eventType + " taskId does not match active task");
        }
        return state.activeTaskId();
    }

    private void ensureReasoning(List<StreamEvent> events, String reasoningId, String taskId) {
        if (state.activeReasoningId() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reasoningId", reasoningId);
            payload.put("runId", context.runId());
            StreamEventSupport.putIfNonNull(payload, "taskId", taskId);
            StreamEventSupport.putActor(payload, context.requestActor());
            events.add(next("reasoning.start", payload));
            state.setActiveReasoningId(reasoningId);
            return;
        }
        if (!state.activeReasoningId().equals(reasoningId)) {
            closeReasoning(events);
            ensureReasoning(events, reasoningId, taskId);
        }
    }

    private void ensureContent(List<StreamEvent> events, String contentId, String taskId) {
        if (state.activeContentId() == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contentId", contentId);
            payload.put("runId", context.runId());
            StreamEventSupport.putIfNonNull(payload, "taskId", taskId);
            StreamEventSupport.putActor(payload, context.requestActor());
            events.add(next("content.start", payload));
            state.setActiveContentId(contentId);
            return;
        }
        if (!state.activeContentId().equals(contentId)) {
            closeContent(events);
            ensureContent(events, contentId, taskId);
        }
    }

    private void errorRun(List<StreamEvent> events, Map<String, Object> error, RunActor actor) {
        if (state.terminated()) {
            return;
        }
        closeOpenBlocks(events);
        emitRunError(events, error, actor == null ? context.requestActor() : actor);
    }

    private void closeOpenBlocks(List<StreamEvent> events) {
        closeTextBlocks(events);
        closeOpenTools(events);
        closeOpenActions(events);
    }

    private void closeTextBlocks(List<StreamEvent> events) {
        closeReasoning(events);
        closeContent(events);
    }

    private void closeReasoning(List<StreamEvent> events) {
        if (state.activeReasoningId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasoningId", state.activeReasoningId());
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("reasoning.end", payload));
        state.setActiveReasoningId(null);
    }

    private void closeContent(List<StreamEvent> events) {
        if (state.activeContentId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentId", state.activeContentId());
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("content.end", payload));
        state.setActiveContentId(null);
    }

    private void closeOpenTools(List<StreamEvent> events) {
        for (String toolId : state.openToolIds()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", toolId);
            StreamEventSupport.putActor(payload, context.requestActor());
            events.add(next("tool.end", payload));
            state.closeTool(toolId);
        }
    }

    private void closeOpenActions(List<StreamEvent> events) {
        for (String actionId : state.openActionIds()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", actionId);
            StreamEventSupport.putActor(payload, context.requestActor());
            events.add(next("action.end", payload));
            state.closeAction(actionId);
        }
    }

    private void emitTaskStart(List<StreamEvent> events, String taskId, String taskName, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("runId", context.runId());
        StreamEventSupport.putIfNonNull(payload, "taskName", taskName);
        StreamEventSupport.putIfNonNull(payload, "description", description);
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("task.start", payload));
        state.setActiveTaskId(taskId);
    }

    private void emitTaskComplete(List<StreamEvent> events, String taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("task.complete", payload));
        state.setActiveTaskId(null);
    }

    private void emitTaskCancel(List<StreamEvent> events, String taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("task.cancel", payload));
        state.setActiveTaskId(null);
    }

    private void emitTaskFail(List<StreamEvent> events, String taskId, Map<String, Object> error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("error", error);
        StreamEventSupport.putActor(payload, context.requestActor());
        events.add(next("task.fail", payload));
        state.setActiveTaskId(null);
    }

    private void emitRunError(List<StreamEvent> events, Map<String, Object> error, RunActor actor) {
        if (state.activeTaskId() != null) {
            emitTaskFail(events, state.activeTaskId(), error);
        }
        Map<String, Object> runError = new LinkedHashMap<>();
        runError.put("runId", context.runId());
        runError.put("error", error);
        StreamEventSupport.putActor(runError, actor);
        events.add(next("run.error", runError));
        state.setTerminated(true);
    }

    private void ensureActiveTask(String taskId, String eventType) {
        if (state.activeTaskId() == null) {
            throw new IllegalStateException(eventType + " requires an active task");
        }
        if (!state.activeTaskId().equals(taskId)) {
            throw new IllegalStateException(eventType + " taskId does not match active task");
        }
    }

    private void ensureRunContext() {
        if (!hasRunContext()) {
            throw new IllegalStateException("current request type does not provide run context");
        }
    }

    private void ensureChatContext() {
        if (context.chatId() == null) {
            throw new IllegalStateException("current request type does not provide chat context");
        }
    }

    private StreamEvent next(String type, Map<String, Object> payload) {
        return eventFactory.next(type, payload);
    }

    @FunctionalInterface
    interface EventFactory {
        StreamEvent next(String type, Map<String, Object> payload);
    }

    private record RequestContext(
            StreamRequest request,
            String chatId,
            String runId,
            RunActor requestActor
    ) {
    }
}
