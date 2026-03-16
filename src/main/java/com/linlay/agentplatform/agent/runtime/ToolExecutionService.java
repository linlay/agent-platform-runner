package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.PlannedToolCall;
import com.linlay.agentplatform.agent.PlanToolConstants;
import com.linlay.agentplatform.agent.ToolAppend;
import com.linlay.agentplatform.agent.ToolArgumentResolver;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.IdGenerators;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class ToolExecutionService {

    public static final String FRONTEND_SUBMIT_TIMEOUT_CODE = "frontend_submit_timeout";
    public static final String TOOL_NOT_REGISTERED_CODE = "tool_not_registered";
    public static final Set<String> FATAL_TOOL_ERROR_CODES = Set.of(
            TOOL_NOT_REGISTERED_CODE,
            "mcp_tool_missing",
            "mcp_server_not_found",
            "mcp_server_unavailable"
    );
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private static final ExecutorService BACKEND_TOOL_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-platform-backend-tool");
        thread.setDaemon(true);
        return thread;
    });

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver toolArgumentResolver;
    private final ObjectMapper objectMapper;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ToolInvoker toolInvoker;
    private final PlanTaskDeltaBuilder planTaskDeltaBuilder;
    private final ToolInvocationLogger toolInvocationLogger;

    public ToolExecutionService(
            ToolRegistry toolRegistry,
            ToolArgumentResolver toolArgumentResolver,
            ObjectMapper objectMapper,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker
    ) {
        this.toolRegistry = toolRegistry;
        this.toolArgumentResolver = toolArgumentResolver;
        this.objectMapper = objectMapper;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
        this.loggingAgentProperties = loggingAgentProperties == null ? new LoggingAgentProperties() : loggingAgentProperties;
        this.toolInvoker = toolInvoker == null
                ? (name, args, context) -> this.toolRegistry.invoke(name, args)
                : toolInvoker;
        this.planTaskDeltaBuilder = new PlanTaskDeltaBuilder(objectMapper);
        this.toolInvocationLogger = new ToolInvocationLogger(log, this.loggingAgentProperties);
    }

    public ToolExecutionBatch executeToolCalls(
            List<PlannedToolCall> calls,
            Map<String, BaseTool> enabledToolsByName,
            List<Map<String, Object>> records,
            String runId,
            ExecutionContext context,
            boolean emitToolCallDelta
    ) {
        return executeToolCalls(calls, enabledToolsByName, records, runId, context, emitToolCallDelta, null, null);
    }

    public ToolExecutionBatch executeToolCalls(
            List<PlannedToolCall> calls,
            Map<String, BaseTool> enabledToolsByName,
            List<Map<String, Object>> records,
            String runId,
            ExecutionContext context,
            boolean emitToolCallDelta,
            String taskId
    ) {
        return executeToolCalls(calls, enabledToolsByName, records, runId, context, emitToolCallDelta, taskId, null);
    }

    public ToolExecutionBatch executeToolCalls(
            List<PlannedToolCall> calls,
            Map<String, BaseTool> enabledToolsByName,
            List<Map<String, Object>> records,
            String runId,
            ExecutionContext context,
            boolean emitToolCallDelta,
            String taskId,
            Consumer<AgentDelta> preExecutionEmitter
    ) {
        if (calls == null || calls.isEmpty()) {
            return new ToolExecutionBatch(List.of(), List.of());
        }

        List<AgentDelta> deltas = new ArrayList<>();
        List<ToolExecutionEvent> events = new ArrayList<>();
        List<PreparedToolCall> preparedCalls = new ArrayList<>();

        int seq = 0;
        for (PlannedToolCall call : calls) {
            if (call == null) {
                continue;
            }
            seq++;
            String toolName = normalizeToolName(call.name());
            String callId = StringUtils.hasText(call.callId())
                    ? call.callId().trim()
                    : "call_" + toolName + "_" + seq + "_" + shortId();
            String toolType = toolRegistry.toolCallType(toolName);

            Map<String, Object> plannedArgs = call.arguments() == null ? Map.of() : call.arguments();
            Map<String, Object> resolvedArgs = toolArgumentResolver.resolveToolArguments(toolName, plannedArgs, records);
            String argsJson = toJson(resolvedArgs);
            preparedCalls.add(new PreparedToolCall(callId, toolName, toolType, resolvedArgs, argsJson));

            if (emitToolCallDelta) {
                deltas.add(AgentDelta.toolCalls(List.of(new ToolCallDelta(callId, toolType, toolName, argsJson)), taskId));
                if (!isActionType(toolType)) {
                    deltas.add(AgentDelta.toolEnd(callId));
                }
            }
        }

        for (PreparedToolCall call : preparedCalls) {
            failIfInterrupted(context);
            if (context != null) {
                context.runControl().transitionState(RunLoopState.TOOL_EXECUTING);
            }
            long invokeStartNanos = System.nanoTime();
            toolInvocationLogger.logInvocationStart(
                    runId,
                    taskId,
                    call.callId(),
                    call.toolName(),
                    call.toolType(),
                    call.argsJson()
            );
            InvokeResult invokeResult = invokeByKind(
                    runId,
                    call.callId(),
                    call.toolName(),
                    call.toolType(),
                    call.resolvedArgs(),
                    enabledToolsByName,
                    context
            );
            if (invokeResult.submitDelta() != null) {
                appendDelta(deltas, preExecutionEmitter, invokeResult.submitDelta());
            }
            JsonNode resultNode = invokeResult.resultNode();
            String resultText = toResultText(resultNode);
            deltas.add(AgentDelta.toolResult(call.callId(), resultText));
            records.add(buildToolRecord(call.callId(), call.toolName(), call.toolType(), call.resolvedArgs(), resultNode));
            events.add(new ToolExecutionEvent(call.callId(), call.toolName(), call.toolType(), call.argsJson(), resultText));
            toolInvocationLogger.logInvocationEnd(
                    runId,
                    taskId,
                    call.callId(),
                    call.toolName(),
                    call.toolType(),
                    resultNode,
                    (System.nanoTime() - invokeStartNanos) / 1_000_000L
            );

            AgentDelta planDelta = planTaskDeltaBuilder.planUpdateDelta(context, call.toolName(), call.resolvedArgs(), resultNode);
            if (planDelta != null) {
                deltas.add(planDelta);
            }

            if (isFatalToolError(resultNode)) {
                break;
            }
        }

        if (context != null && !context.isInterrupted()) {
            context.runControl().transitionState(RunLoopState.IDLE);
        }

        return new ToolExecutionBatch(List.copyOf(deltas), List.copyOf(events));
    }

    private void appendDelta(List<AgentDelta> deltas, Consumer<AgentDelta> preExecutionEmitter, AgentDelta delta) {
        if (delta == null) {
            return;
        }
        if (preExecutionEmitter != null) {
            preExecutionEmitter.accept(delta);
            return;
        }
        deltas.add(delta);
    }

    public List<com.linlay.agentplatform.service.LlmService.LlmFunctionTool> enabledFunctionTools(Map<String, BaseTool> enabledToolsByName) {
        if (enabledToolsByName == null || enabledToolsByName.isEmpty()) {
            return List.of();
        }
        return enabledToolsByName.values().stream()
                .sorted(java.util.Comparator.comparing(BaseTool::name))
                .map(tool -> new com.linlay.agentplatform.service.LlmService.LlmFunctionTool(
                        normalizeToolName(tool.name()),
                        normalize(tool.description()),
                        tool.parametersSchema(),
                        false
                ))
                .toList();
    }

    public PlanSnapshot planSnapshot(ExecutionContext context) {
        if (context == null) {
            return new PlanSnapshot("plan_default", null, List.of());
        }
        String planId = context.planId();
        String chatId = context.request() == null ? null : context.request().chatId();
        return new PlanSnapshot(planId, chatId, context.planTasks());
    }

    public String applyBackendPrompts(String systemPrompt, Map<String, BaseTool> stageTools) {
        return applyBackendPrompts(systemPrompt, stageTools, ToolAppend.DEFAULTS, true);
    }

    public String applyBackendPrompts(
            String systemPrompt,
            Map<String, BaseTool> stageTools,
            boolean includeAfterCallHints
    ) {
        return applyBackendPrompts(systemPrompt, stageTools, ToolAppend.DEFAULTS, includeAfterCallHints);
    }

    public String applyBackendPrompts(
            String systemPrompt,
            Map<String, BaseTool> stageTools,
            ToolAppend toolAppend,
            boolean includeAfterCallHints
    ) {
        ToolAppend effective = toolAppend == null ? ToolAppend.DEFAULTS : toolAppend;
        String base = normalize(systemPrompt);
        List<String> sections = new ArrayList<>();
        // Tool appendix is always merged into system prompt after stage/skill sections.
        sections.add(backendToolDescriptionSection(stageTools, effective.toolDescriptionTitle()));
        if (includeAfterCallHints) {
            sections.add(backendAfterCallHintSection(stageTools, effective.afterCallHintTitle()));
        }
        List<String> filteredSections = sections.stream()
                .filter(StringUtils::hasText)
                .toList();
        if (filteredSections.isEmpty()) {
            return base;
        }
        String appendix = String.join("\n\n", filteredSections);
        if (!StringUtils.hasText(base)) {
            return appendix;
        }
        return base + "\n\n" + appendix;
    }

    public String backendToolDescriptionSection(Map<String, BaseTool> stageTools, String sectionTitle) {
        return formatToolSection(sectionTitle, backendDescriptionLines(stageTools));
    }

    public String backendAfterCallHintSection(Map<String, BaseTool> stageTools, String sectionTitle) {
        return formatToolSection(sectionTitle, backendAfterCallHintLines(stageTools));
    }

    private List<String> backendDescriptionLines(Map<String, BaseTool> stageTools) {
        return backendTools(stageTools).stream()
                .map(tool -> {
                    String description = normalize(tool.description());
                    if (!StringUtils.hasText(description)) {
                        return null;
                    }
                    return "- " + normalizeToolName(tool.name()) + ": " + description;
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> backendAfterCallHintLines(Map<String, BaseTool> stageTools) {
        return backendTools(stageTools).stream()
                .map(tool -> {
                    String afterCallHint = normalize(tool.afterCallHint());
                    if (!StringUtils.hasText(afterCallHint)) {
                        return null;
                    }
                    return "- " + normalizeToolName(tool.name()) + ": " + afterCallHint;
                })
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<BaseTool> backendTools(Map<String, BaseTool> stageTools) {
        if (stageTools == null || stageTools.isEmpty()) {
            return List.of();
        }
        return stageTools.values().stream()
                .filter(tool -> tool != null && StringUtils.hasText(tool.name()))
                .filter(tool -> isBackendTool(tool.name()))
                .sorted(Comparator.comparing(tool -> normalizeToolName(tool.name())))
                .toList();
    }

    private String formatToolSection(String sectionTitle, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String title = StringUtils.hasText(sectionTitle)
                ? sectionTitle.trim()
                : ToolAppend.DEFAULTS.toolDescriptionTitle();
        return title + "\n" + String.join("\n", lines);
    }

    private InvokeResult invokeByKind(
            String runId,
            String toolId,
            String toolName,
            String toolType,
            Map<String, Object> args,
            Map<String, BaseTool> enabledToolsByName,
            ExecutionContext context
    ) {
        failIfInterrupted(context);
        if (!enabledToolsByName.containsKey(toolName)) {
            return new InvokeResult(errorResult(toolName, "Tool is not enabled for this agent: " + toolName), null);
        }
        if (toolRegistry.descriptor(toolName).isEmpty()) {
            return new InvokeResult(
                    errorResult(toolName, TOOL_NOT_REGISTERED_CODE, "Tool is not registered: " + toolName),
                    null
            );
        }

        if (PlanToolConstants.PLAN_GET_TASKS_TOOL.equals(toolName)) {
            return new InvokeResult(planTaskDeltaBuilder.planGetResult(planSnapshot(context)), null);
        }

        if ("action".equalsIgnoreCase(toolType)) {
            return new InvokeResult(objectMapper.getNodeFactory().textNode("OK"), null);
        }

        if (toolRegistry.requiresFrontendSubmit(toolName)) {
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
                    return new InvokeResult(errorResult(toolName, FRONTEND_SUBMIT_TIMEOUT_CODE, resolveErrorMessage(ex)), null);
                }
                return new InvokeResult(errorResult(toolName, resolveErrorMessage(ex)), null);
            } finally {
                if (!context.isInterrupted()) {
                    context.runControl().transitionState(RunLoopState.TOOL_EXECUTING);
                }
            }
        }

        return new InvokeResult(invokeBackendWithPolicy(toolName, args, context), null);
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
        Budget.Scope scope = context == null || context.budget() == null
                ? Budget.DEFAULT.tool()
                : context.budget().tool();
        if (context != null && context.runControl() != null) {
            return context.runControl().awaitSubmit(runId, toolId, scope.timeoutMs());
        }
        return frontendSubmitCoordinator.awaitSubmit(runId.trim(), toolId).block();
    }

    private JsonNode invokeBackendWithPolicy(
            String toolName,
            Map<String, Object> args,
            ExecutionContext context
    ) {
        failIfInterrupted(context);
        Budget.Scope scope = context == null || context.budget() == null
                ? Budget.DEFAULT.tool()
                : context.budget().tool();
        int retries = Math.max(0, scope.retryCount());
        long timeoutMs = Math.max(1L, scope.timeoutMs());

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return invokeBackendOnce(toolName, args, timeoutMs, context);
            } catch (IllegalArgumentException ex) {
                toolInvocationLogger.logToolExecutionFailure(toolName, attempt, retries, ex);
                return errorResult(toolName, ex.getMessage());
            } catch (TimeoutException ex) {
                toolInvocationLogger.logToolExecutionFailure(toolName, attempt, retries, ex);
                if (attempt >= retries) {
                    return errorResult(toolName, ex.getMessage());
                }
            } catch (RuntimeException ex) {
                toolInvocationLogger.logToolExecutionFailure(toolName, attempt, retries, ex);
                if (attempt >= retries) {
                    return errorResult(toolName, ex.getMessage());
                }
            }
        }

        return errorResult(toolName, "Tool invocation failed after retries");
    }

    private JsonNode invokeBackendOnce(
            String toolName,
            Map<String, Object> args,
            long timeoutMs,
            ExecutionContext context
    ) throws TimeoutException {
        Future<JsonNode> future = BACKEND_TOOL_EXECUTOR.submit(() -> toolInvoker.invoke(toolName, args, context));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new TimeoutException("Backend tool timeout: tool=" + toolName + ", timeoutMs=" + timeoutMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (context != null && context.isInterrupted()) {
                throw new RunInterruptedException();
            }
            throw new RuntimeException("Backend tool invocation interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (isInterrupted(context, cause)) {
                throw new RunInterruptedException();
            }
            if (cause instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause == null ? ex : cause);
        }
    }

    private Map<String, Object> buildToolRecord(
            String callId,
            String toolName,
            String toolType,
            Map<String, Object> args,
            JsonNode result
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("callId", callId);
        record.put("toolName", toolName);
        record.put("toolType", toolType);
        record.put("arguments", args == null ? Map.of() : args);
        record.put("result", result);
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
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

    private boolean isActionType(String toolType) {
        return "action".equalsIgnoreCase(toolType);
    }

    private String normalizeToolName(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }

    private String shortId() {
        return IdGenerators.shortHexId();
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private boolean isBackendTool(String toolName) {
        return toolRegistry.descriptor(toolName)
                .map(descriptor -> descriptor.kind() == ToolKind.BACKEND)
                .orElse(true);
    }

    private boolean isFatalToolError(JsonNode resultNode) {
        if (resultNode == null || !resultNode.isObject()) {
            return false;
        }
        if (resultNode.path("ok").asBoolean(true)) {
            return false;
        }
        String code = normalize(resultNode.path("code").asText("")).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return FATAL_TOOL_ERROR_CODES.contains(code);
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

    public record ToolExecutionEvent(
            String callId,
            String toolName,
            String toolType,
            String argsJson,
            String resultText
    ) {
    }

    private record PreparedToolCall(
            String callId,
            String toolName,
            String toolType,
            Map<String, Object> resolvedArgs,
            String argsJson
    ) {
    }

    private record InvokeResult(
            JsonNode resultNode,
            AgentDelta submitDelta
    ) {
    }

    public record ToolExecutionBatch(
            List<AgentDelta> deltas,
            List<ToolExecutionEvent> events
    ) {
    }

    public record PlanSnapshot(
            String planId,
            String chatId,
            List<AgentDelta.PlanTask> tasks
    ) {
        public PlanSnapshot {
            if (tasks == null) {
                tasks = List.of();
            } else {
                tasks = List.copyOf(tasks);
            }
        }
    }
}
