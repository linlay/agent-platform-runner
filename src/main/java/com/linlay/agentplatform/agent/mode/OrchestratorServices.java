package com.linlay.agentplatform.agent.mode;

import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.PlannedToolCall;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.FatalToolExecutionException;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitTimeoutException;
import com.linlay.agentplatform.agent.runtime.ModelTimeoutException;
import com.linlay.agentplatform.agent.runtime.RunControl;
import com.linlay.agentplatform.agent.runtime.RunInputBroker;
import com.linlay.agentplatform.agent.runtime.RunInterruptedException;
import com.linlay.agentplatform.agent.runtime.RunLoopState;
import com.linlay.agentplatform.agent.runtime.ToolExecutionService;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.service.llm.LlmCallSpec;
import com.linlay.agentplatform.service.llm.LlmService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

public class OrchestratorServices {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorServices.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern STEP_PREFIX = Pattern.compile(
            "^(?:[-*•]|\\d+[.)]|步骤\\s*\\d+[:：.)]?|[一二三四五六七八九十]+[、.)])\\s*(.+)$"
    );
    private static final String FRONTEND_TIMEOUT_MESSAGE = "前端工具等待用户提交超时，本次运行已结束。请重新发起或在超时前提交。";

    private final LlmService llmService;
    private final ToolExecutionService toolExecutionService;
    private final ObjectMapper objectMapper;
    private final ModelTurnAccumulator modelTurnAccumulator;
    private final int defaultMaxTokens;

    public OrchestratorServices(
            LlmService llmService,
            ToolExecutionService toolExecutionService,
            ObjectMapper objectMapper
    ) {
        this(llmService, toolExecutionService, objectMapper, null);
    }

    public OrchestratorServices(
            LlmService llmService,
            ToolExecutionService toolExecutionService,
            ObjectMapper objectMapper,
            AgentDefaultsProperties defaults
    ) {
        this.llmService = llmService;
        this.toolExecutionService = toolExecutionService;
        this.objectMapper = objectMapper;
        this.modelTurnAccumulator = new ModelTurnAccumulator(objectMapper);
        this.defaultMaxTokens = defaults == null ? 4096 : defaults.defaultMaxTokens();
    }

    public LlmService llmService() {
        return llmService;
    }

    public ToolExecutionService toolExecutionService() {
        return toolExecutionService;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public record ModelTurn(
            String finalText,
            String reasoningText,
            List<PlannedToolCall> toolCalls,
            Map<String, Object> usage
    ) {
    }

    public ModelTurn callModelTurnStreaming(
            ExecutionContext context,
            StageSettings stageSettings,
            List<ChatMessage> messages,
            String userPrompt,
            Map<String, BaseTool> stageTools,
            List<LlmService.LlmFunctionTool> tools,
            ToolChoice toolChoice,
            String stage,
            boolean parallelToolCalls,
            boolean emitReasoning,
            boolean emitContent,
            boolean emitToolCalls,
            FluxSink<AgentDelta> sink
    ) {
        return callModelTurnStreaming(
                context,
                stageSettings,
                messages,
                userPrompt,
                stageTools,
                tools,
                toolChoice,
                stage,
                parallelToolCalls,
                emitReasoning,
                emitContent,
                emitToolCalls,
                true,
                sink
        );
    }

    public ModelTurn callModelTurnStreaming(
            ExecutionContext context,
            StageSettings stageSettings,
            List<ChatMessage> messages,
            String userPrompt,
            Map<String, BaseTool> stageTools,
            List<LlmService.LlmFunctionTool> tools,
            ToolChoice toolChoice,
            String stage,
            boolean parallelToolCalls,
            boolean emitReasoning,
            boolean emitContent,
            boolean emitToolCalls,
            boolean includeAfterCallHints,
            FluxSink<AgentDelta> sink
    ) {
        Objects.requireNonNull(stageSettings, "stageSettings must not be null");
        failIfInterrupted(context);
        injectPendingSteers(context, sink);
        failIfInterrupted(context);
        context.incrementModelCalls();
        String stageSystemPrompt = context.stageSystemPrompt(
                stageSettings.instructionsPrompt(),
                stageSettings.systemPrompt()
        );
        String mergedSystemPrompt = stageSystemPrompt;
        // System prompt merge order is strict:
        // 1) stage system prompt (agent.json stage-level prompt),
        // 2) skill catalog blocks (system),
        // 3) backend tool appendix (system).
        String effectiveSystemPrompt = toolExecutionService.applyBackendPrompts(
                mergedSystemPrompt,
                stageTools,
                context.definition().agentMode().toolAppend(),
                includeAfterCallHints
        );

        context.runControl().transitionState(RunLoopState.MODEL_STREAMING);
        long modelStartNanos = System.nanoTime();
        try {
            return modelTurnAccumulator.accumulate(
                    llmService.streamDeltas(new LlmCallSpec(
                    resolveProvider(stageSettings, context),
                    resolveModel(stageSettings, context),
                    resolveProtocol(stageSettings, context),
                    effectiveSystemPrompt,
                    messages,
                    userPrompt,
                    tools,
                    toolChoice,
                    null,
                    resolveEffort(stageSettings),
                    stageSettings.reasoningEnabled(),
                    stageSettings.maxTokens() != null ? stageSettings.maxTokens() : defaultMaxTokens,
                    context.budget().model().timeoutMs(),
                    stage,
                    parallelToolCalls,
                    context.runControl().cancelSignal()
            )).toIterable(),
                    context,
                    stage,
                    emitReasoning,
                    emitContent,
                    emitToolCalls,
                    sink
            );
        } catch (RuntimeException ex) {
            ModelTimeoutException timeout = classifyModelTimeout(ex, stage, context, modelStartNanos);
            if (timeout != null) {
                throw timeout;
            }
            throw ex;
        } finally {
            if (!context.isInterrupted()) {
                context.runControl().transitionState(RunLoopState.IDLE);
            }
        }
    }

    public void executeToolsAndEmit(
            ExecutionContext context,
            Map<String, BaseTool> enabledToolsByName,
            List<PlannedToolCall> plannedToolCalls,
            FluxSink<AgentDelta> sink
    ) {
        failIfInterrupted(context);
        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                plannedToolCalls,
                enabledToolsByName,
                context.toolRecords(),
                context.request().runId(),
                context,
                false,
                context.activeTaskId(),
                delta -> emit(sink, delta)
        );
        FrontendSubmitTimeoutException frontendTimeout = detectFrontendSubmitTimeout(batch.events());
        for (AgentDelta delta : batch.deltas()) {
            emit(sink, delta);
        }
        appendToolEvents(context.conversationMessages(), batch.events());
        appendToolEvents(context.executeMessages(), batch.events());
        if (frontendTimeout != null) {
            throw frontendTimeout;
        }
        FatalToolExecutionException fatalToolError = detectFatalToolExecution(batch.events());
        if (fatalToolError != null) {
            throw fatalToolError;
        }
        context.incrementToolCalls(batch.events().size());
        failIfInterrupted(context);
    }

    public void emitFinalAnswer(
            String candidateFinalText,
            boolean contentAlreadyEmitted,
            FluxSink<AgentDelta> sink
    ) {
        if (!contentAlreadyEmitted && StringUtils.hasText(candidateFinalText)) {
            emit(sink, AgentDelta.content(candidateFinalText));
        }
    }

    public Map<String, BaseTool> selectTools(Map<String, BaseTool> enabledToolsByName, List<String> configuredTools) {
        if (enabledToolsByName == null || enabledToolsByName.isEmpty()) {
            return Map.of();
        }
        return selectTools(
                enabledToolsByName.keySet().stream().toList(),
                configuredTools,
                enabledToolsByName::get
        );
    }

    public Map<String, BaseTool> selectTools(
            List<String> configuredAgentTools,
            List<String> configuredStageTools,
            Function<String, BaseTool> toolResolver
    ) {
        if (toolResolver == null) {
            return Map.of();
        }
        List<String> effective = configuredStageTools == null || configuredStageTools.isEmpty()
                ? (configuredAgentTools == null ? List.of() : configuredAgentTools)
                : configuredStageTools;
        Map<String, BaseTool> selected = new LinkedHashMap<>();
        for (String raw : effective) {
            String name = normalize(raw).toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(name)) {
                continue;
            }
            BaseTool tool = toolResolver.apply(name);
            if (tool != null) {
                selected.put(name, tool);
            }
        }
        return Map.copyOf(selected);
    }

    public void emit(FluxSink<AgentDelta> sink, AgentDelta delta) {
        if (delta == null || sink.isCancelled()) {
            return;
        }
        sink.next(delta);
    }

    public boolean requiresTool(ExecutionContext context) {
        return context.definition().runSpec().toolChoice() == ToolChoice.REQUIRED;
    }

    public boolean allowsTool(ExecutionContext context) {
        return context.definition().runSpec().toolChoice() != ToolChoice.NONE;
    }

    public int modelRetryCount(ExecutionContext context, int fallback) {
        if (context == null || context.budget() == null) {
            return fallback;
        }
        int configured = context.budget().model().retryCount();
        return configured > 0 ? configured : fallback;
    }

    public int toolRetryCount(ExecutionContext context, int fallback) {
        if (context == null || context.budget() == null) {
            return fallback;
        }
        int configured = context.budget().tool().retryCount();
        return configured > 0 ? configured : fallback;
    }

    public void appendAssistantMessage(List<ChatMessage> messages, String text) {
        String normalized = normalize(text);
        if (!normalized.isBlank()) {
            messages.add(new ChatMessage.AssistantMsg(normalized));
        }
    }

    public String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }

    public List<PlanStep> parsePlanSteps(String raw) {
        JsonNode root = readJson(raw);
        if (root != null && root.isObject() && root.path("steps").isArray()) {
            List<PlanStep> steps = new ArrayList<>();
            int index = 0;
            for (JsonNode node : root.path("steps")) {
                index++;
                String id = normalize(node.path("id").asText("step-" + index));
                String title = normalize(node.path("title").asText("Step " + index));
                String goal = normalize(node.path("goal").asText(title));
                String success = normalize(node.path("successCriteria").asText("完成步骤"));
                steps.add(new PlanStep(id, title, goal, success));
            }
            if (!steps.isEmpty()) {
                return steps;
            }
        }

        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<PlanStep> steps = new ArrayList<>();
        String normalized = raw.replace("\r\n", "\n");
        int index = 0;
        for (String line : normalized.split("\n")) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String trimmed = line.trim();
            java.util.regex.Matcher matcher = STEP_PREFIX.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            String content = normalize(matcher.group(1));
            if (content.isBlank()) {
                continue;
            }
            index++;
            String id = "step-" + index;
            String title = content;
            String goal = content;
            String success = "完成任务: " + content;
            steps.add(new PlanStep(id, title, goal, success));
        }
        return List.copyOf(steps);
    }

    public record PlanStep(
            String id,
            String title,
            String goal,
            String successCriteria
    ) {
    }

    private void appendToolEvents(List<ChatMessage> messages, List<ToolExecutionService.ToolExecutionEvent> events) {
        for (ToolExecutionService.ToolExecutionEvent event : events) {
            ChatMessage.AssistantMsg.ToolCall toolCall = new ChatMessage.AssistantMsg.ToolCall(
                    event.callId(),
                    event.toolType(),
                    event.toolName(),
                    event.argsJson()
            );
            messages.add(new ChatMessage.AssistantMsg("", List.of(toolCall)));

            ChatMessage.ToolResultMsg.ToolResponse toolResponse = new ChatMessage.ToolResultMsg.ToolResponse(
                    event.callId(),
                    event.toolName(),
                    event.resultText()
            );
            messages.add(new ChatMessage.ToolResultMsg(List.of(toolResponse)));
        }
    }

    private FrontendSubmitTimeoutException detectFrontendSubmitTimeout(List<ToolExecutionService.ToolExecutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (ToolExecutionService.ToolExecutionEvent event : events) {
            if (event == null || !StringUtils.hasText(event.resultText())) {
                continue;
            }
            try {
                JsonNode result = objectMapper.readTree(event.resultText());
                if (!result.isObject()) {
                    continue;
                }
                String code = result.path("code").asText(null);
                if (!ToolExecutionService.FRONTEND_SUBMIT_TIMEOUT_CODE.equals(code)) {
                    continue;
                }
                String rawMessage = result.path("error").asText(null);
                String message = StringUtils.hasText(rawMessage) ? rawMessage.trim() : FRONTEND_TIMEOUT_MESSAGE;
                long timeoutMs = result.path("diagnostics").path("timeoutMs").asLong(0L);
                long elapsedMs = result.path("diagnostics").path("elapsedMs").asLong(0L);
                return new FrontendSubmitTimeoutException(
                        message,
                        event.callId(),
                        event.toolName(),
                        event.resultText(),
                        timeoutMs,
                        elapsedMs
                );
            } catch (Exception ignored) {
                // ignore malformed tool results and continue scanning
            }
        }
        return null;
    }

    private FatalToolExecutionException detectFatalToolExecution(List<ToolExecutionService.ToolExecutionEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (ToolExecutionService.ToolExecutionEvent event : events) {
            if (event == null || !StringUtils.hasText(event.resultText())) {
                continue;
            }
            try {
                JsonNode result = objectMapper.readTree(event.resultText());
                if (!result.isObject()) {
                    continue;
                }
                String code = normalize(result.path("code").asText("")).toLowerCase(Locale.ROOT);
                if (!ToolExecutionService.FATAL_TOOL_ERROR_CODES.contains(code)) {
                    continue;
                }
                String rawMessage = result.path("error").asText(null);
                String message = StringUtils.hasText(rawMessage)
                        ? rawMessage.trim()
                        : "Tool invocation failed: " + normalize(event.toolName());
                return new FatalToolExecutionException(code, message, event.callId(), event.toolName());
            } catch (Exception ignored) {
                // ignore malformed tool results and continue scanning
            }
        }
        return null;
    }

    private ModelTimeoutException classifyModelTimeout(
            RuntimeException ex,
            String stage,
            ExecutionContext context,
            long modelStartNanos
    ) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                long timeoutMs = context == null || context.budget() == null
                        ? 0L
                        : Math.max(1L, context.budget().model().timeoutMs());
                long elapsedMs = Math.max(1L, (System.nanoTime() - modelStartNanos) / 1_000_000L);
                String normalizedStage = normalize(stage);
                String message = "模型调用超时：stage="
                        + (StringUtils.hasText(normalizedStage) ? normalizedStage : "unknown")
                        + ", elapsedMs="
                        + elapsedMs
                        + ", timeoutMs="
                        + timeoutMs;
                return new ModelTimeoutException(message, normalizedStage, timeoutMs, elapsedMs, ex);
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private JsonNode readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).trim();
            if (normalized.startsWith("json")) {
                normalized = normalized.substring(4).trim();
            }
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ex) {
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(normalized.substring(start, end + 1));
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    private String resolveProvider(StageSettings stageSettings, ExecutionContext context) {
        String provider = normalize(stageSettings.providerKey());
        if (StringUtils.hasText(provider)) {
            return provider;
        }
        return context.definition().providerKey();
    }

    private String resolveModel(StageSettings stageSettings, ExecutionContext context) {
        String model = normalize(stageSettings.model());
        if (StringUtils.hasText(model)) {
            return model;
        }
        return context.definition().model();
    }

    private ModelProtocol resolveProtocol(StageSettings stageSettings, ExecutionContext context) {
        if (stageSettings.protocol() != null) {
            return stageSettings.protocol();
        }
        return context.definition().protocol();
    }

    private String mergeSystemPrompt(String base, String appendix) {
        boolean hasBase = StringUtils.hasText(base);
        boolean hasAppendix = StringUtils.hasText(appendix);
        if (!hasBase) {
            return hasAppendix ? appendix : null;
        }
        if (!hasAppendix) {
            return base;
        }
        return base + "\n\n" + appendix;
    }

    private ComputePolicy resolveEffort(StageSettings stageSettings) {
        return stageSettings.reasoningEffort() == null ? ComputePolicy.MEDIUM : stageSettings.reasoningEffort();
    }

    private boolean isPlanGenerateStage(String stage) {
        return "agent-plan-generate".equals(stage);
    }

    private void injectPendingSteers(ExecutionContext context, FluxSink<AgentDelta> sink) {
        if (context == null) {
            return;
        }
        for (RunInputBroker.SteerEnvelope steer : context.drainPendingSteers()) {
            if (steer == null || !StringUtils.hasText(steer.message())) {
                continue;
            }
            String message = steer.message().trim();
            context.appendHumanMessageToAllContexts(message);
            emit(sink, AgentDelta.userMessage(message, context.activeTaskId()));
            emit(sink, AgentDelta.requestSteer(steer.requestId(), steer.steerId(), message));
        }
    }

    private void failIfInterrupted(ExecutionContext context) {
        if (context != null && context.isInterrupted()) {
            throw new RunInterruptedException();
        }
    }
}
