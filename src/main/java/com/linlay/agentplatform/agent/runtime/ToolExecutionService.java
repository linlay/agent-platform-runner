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
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitCoordinator;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolMetadataAware;
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
    private final PlanToolHandler planToolHandler;
    private final FrontendToolHandler frontendToolHandler;

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
        this.planToolHandler = new PlanToolHandler(this.planTaskDeltaBuilder);
        this.frontendToolHandler = new FrontendToolHandler(objectMapper, frontendSubmitCoordinator);
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
            String toolType = resolveToolType(toolName, enabledToolsByName);

            Map<String, Object> plannedArgs = call.arguments() == null ? Map.of() : call.arguments();
            Map<String, Object> resolvedArgs = toolArgumentResolver.resolveToolArguments(toolName, plannedArgs, records);
            String argsJson = toJson(resolvedArgs);
            preparedCalls.add(new PreparedToolCall(callId, toolName, toolType, resolvedArgs, argsJson));
        }

        for (PreparedToolCall call : preparedCalls) {
            failIfInterrupted(context);
            if (context != null) {
                context.runControl().transitionState(RunLoopState.TOOL_EXECUTING);
            }
            if (emitToolCallDelta) {
                appendDelta(
                        deltas,
                        preExecutionEmitter,
                        AgentDelta.toolCalls(List.of(new ToolCallDelta(call.callId(), call.toolType(), call.toolName(), call.argsJson())), taskId)
                );
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
            FrontendToolHandler.InvokeResult invokeResult;
            bindToolContext(context, call.callId(), call.toolName(), taskId, delta -> appendDelta(deltas, preExecutionEmitter, delta));
            try {
                invokeResult = invokeByKind(
                        runId,
                        call.callId(),
                        call.toolName(),
                        call.toolType(),
                        call.resolvedArgs(),
                        enabledToolsByName,
                        context
                );
            } finally {
                clearToolContext(context);
            }
            if (invokeResult.submitDelta() != null) {
                appendDelta(deltas, preExecutionEmitter, invokeResult.submitDelta());
            }
            JsonNode resultNode = invokeResult.resultNode();
            String resultText = toResultText(resultNode);
            deltas.add(AgentDelta.toolResult(call.callId(), resultText));
            if (context != null) {
                deltas.addAll(context.drainDeferredToolDeltas());
            }
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

            AgentDelta planDelta = planToolHandler.planUpdateDelta(context, call.toolName(), call.resolvedArgs(), resultNode);
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

    private void bindToolContext(
            ExecutionContext context,
            String toolId,
            String toolName,
            String taskId,
            Consumer<AgentDelta> deltaEmitter
    ) {
        if (context == null) {
            return;
        }
        context.bindToolInvocation(new ExecutionContext.ToolInvocationContext(toolId, toolName, taskId));
        context.bindDeltaEmitter(deltaEmitter);
    }

    private void clearToolContext(ExecutionContext context) {
        if (context == null) {
            return;
        }
        context.clearDeltaEmitter();
        context.clearToolInvocation();
    }

    public List<com.linlay.agentplatform.service.llm.LlmService.LlmFunctionTool> enabledFunctionTools(Map<String, BaseTool> enabledToolsByName) {
        if (enabledToolsByName == null || enabledToolsByName.isEmpty()) {
            return List.of();
        }
        return enabledToolsByName.values().stream()
                .sorted(java.util.Comparator.comparing(BaseTool::name))
                .map(tool -> new com.linlay.agentplatform.service.llm.LlmService.LlmFunctionTool(
                        normalizeToolName(tool.name()),
                        normalize(tool.description()),
                        tool.parametersSchema(),
                        false
                ))
                .toList();
    }

    public PlanState planState(ExecutionContext context) {
        return planToolHandler.planState(context);
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
                .filter(tool -> isBackendTool(tool.name(), stageTools))
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

    private FrontendToolHandler.InvokeResult invokeByKind(
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
            return new FrontendToolHandler.InvokeResult(errorResult(toolName, "Tool is not enabled for this agent: " + toolName), null);
        }
        ToolDescriptor descriptor = resolveToolDescriptor(toolName, enabledToolsByName);
        if (descriptor == null) {
            return new FrontendToolHandler.InvokeResult(
                    errorResult(toolName, TOOL_NOT_REGISTERED_CODE, "Tool is not registered: " + toolName),
                    null
            );
        }

        if (planToolHandler.handles(toolName)) {
            return new FrontendToolHandler.InvokeResult(planToolHandler.invoke(toolName, context), null);
        }

        if ("action".equalsIgnoreCase(toolType)) {
            return new FrontendToolHandler.InvokeResult(objectMapper.getNodeFactory().textNode("OK"), null);
        }

        if (descriptor.requiresFrontendSubmit()) {
            return frontendToolHandler.invoke(runId, toolId, toolName, context);
        }

        return new FrontendToolHandler.InvokeResult(invokeBackendWithPolicy(toolName, args, context), null);
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

    private boolean isBackendTool(String toolName, Map<String, BaseTool> enabledToolsByName) {
        ToolDescriptor descriptor = resolveToolDescriptor(toolName, enabledToolsByName);
        return descriptor == null || descriptor.kind() == ToolKind.BACKEND;
    }

    private String resolveToolType(String toolName, Map<String, BaseTool> enabledToolsByName) {
        ToolDescriptor descriptor = resolveToolDescriptor(toolName, enabledToolsByName);
        if (descriptor == null) {
            return toolRegistry.toolCallType(toolName);
        }
        if (descriptor.isAction()) {
            return "action";
        }
        if (descriptor.isFrontend()) {
            String normalizedToolType = normalize(descriptor.toolType());
            return StringUtils.hasText(normalizedToolType) ? normalizedToolType : "function";
        }
        return "function";
    }

    private ToolDescriptor resolveToolDescriptor(String toolName, Map<String, BaseTool> enabledToolsByName) {
        if (enabledToolsByName != null) {
            BaseTool enabledTool = enabledToolsByName.get(normalizeToolName(toolName));
            if (enabledTool instanceof ToolMetadataAware metadataAware && metadataAware.descriptor() != null) {
                return metadataAware.descriptor();
            }
        }
        return toolRegistry.descriptor(toolName).orElse(null);
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
            if (cursor instanceof RunInterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
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

    public record ToolExecutionBatch(
            List<AgentDelta> deltas,
            List<ToolExecutionEvent> events
    ) {
    }

    public record PlanState(
            String planId,
            String chatId,
            List<AgentDelta.PlanTask> tasks
    ) {
        public PlanState {
            if (tasks == null) {
                tasks = List.of();
            } else {
                tasks = List.copyOf(tasks);
            }
        }
    }
}
