package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitCoordinator;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

public class FrontendToolHandler {

    private final ObjectMapper objectMapper;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;

    public FrontendToolHandler(ObjectMapper objectMapper, FrontendSubmitCoordinator frontendSubmitCoordinator) {
        this.objectMapper = objectMapper;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
    }

    public InvokeResult invoke(
            String runId,
            String toolId,
            String toolName,
            ExecutionContext context
    ) {
        if (context == null) {
            return new InvokeResult(errorResult(toolName, "Execution context is required for frontend tool submission"), null);
        }
        if (frontendSubmitCoordinator == null && context.runControl() == null) {
            return new InvokeResult(errorResult(toolName, "Frontend submit coordinator is not configured"), null);
        }
        if (!StringUtils.hasText(runId)) {
            return new InvokeResult(errorResult(toolName, "Missing runId for frontend tool submission"), null);
        }
        try {
            context.runControl().transitionState(RunLoopState.WAITING_SUBMIT);
            Object payload = awaitFrontendSubmit(runId.trim(), toolId, context);
            failIfInterrupted(context);
            Object normalized = payload == null ? Map.of() : payload;
            return new InvokeResult(
                    objectMapper.valueToTree(normalized),
                    buildSubmitDelta(context, runId, toolId, normalized)
            );
        } catch (Exception ex) {
            if (isInterrupted(context, ex)) {
                throw new RunInterruptedException();
            }
            if (isFrontendSubmitTimeout(ex)) {
                return new InvokeResult(errorResult(toolName, ToolExecutionService.FRONTEND_SUBMIT_TIMEOUT_CODE, resolveErrorMessage(ex)), null);
            }
            return new InvokeResult(errorResult(toolName, resolveErrorMessage(ex)), null);
        } finally {
            if (!context.isInterrupted()) {
                context.runControl().transitionState(RunLoopState.TOOL_EXECUTING);
            }
        }
    }

    private AgentDelta buildSubmitDelta(
            ExecutionContext context,
            String runId,
            String toolId,
            Object payload
    ) {
        if (context == null || context.request() == null || !StringUtils.hasText(context.request().chatId())) {
            return null;
        }
        String normalizedRunId = StringUtils.hasText(runId) ? runId.trim() : "";
        String normalizedToolId = StringUtils.hasText(toolId) ? toolId.trim() : "";
        if (normalizedRunId.isBlank() || normalizedToolId.isBlank()) {
            return null;
        }
        String requestId = StringUtils.hasText(context.request().requestId())
                ? context.request().requestId().trim()
                : normalizedRunId;
        return AgentDelta.requestSubmit(
                requestId,
                context.request().chatId(),
                normalizedRunId,
                normalizedToolId,
                payload == null ? Map.of() : payload,
                null
        );
    }

    private Object awaitFrontendSubmit(String runId, String toolId, ExecutionContext context) throws TimeoutException {
        if (frontendSubmitCoordinator != null) {
            return frontendSubmitCoordinator.awaitSubmit(runId.trim(), toolId).block();
        }
        Budget.Scope scope = context == null || context.budget() == null
                ? Budget.DEFAULT.tool()
                : context.budget().tool();
        if (context != null && context.runControl() != null) {
            return context.runControl().awaitSubmit(runId, toolId, scope.timeoutMs());
        }
        throw new IllegalStateException("Frontend submit coordinator is not configured");
    }

    private ObjectNode errorResult(String toolName, String message) {
        return errorResult(toolName, null, message);
    }

    private ObjectNode errorResult(String toolName, String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("tool", toolName);
        error.put("ok", false);
        if (StringUtils.hasText(code)) {
            error.put("code", code);
        }
        error.put("error", message == null ? "unknown error" : message);
        return error;
    }

    private boolean isFrontendSubmitTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            String message = cursor.getMessage();
            if (StringUtils.hasText(message) && message.contains("Frontend tool submit timeout")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void failIfInterrupted(ExecutionContext context) {
        if (context != null && context.isInterrupted()) {
            throw new RunInterruptedException();
        }
    }

    private boolean isInterrupted(ExecutionContext context, Throwable throwable) {
        if (context != null && context.isInterrupted()) {
            return true;
        }
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof RunInterruptedException || cursor instanceof CancellationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String resolveErrorMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (StringUtils.hasText(cursor.getMessage())) {
                return cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return "unknown error";
    }

    public record InvokeResult(
            JsonNode resultNode,
            AgentDelta submitDelta
    ) {
    }
}
