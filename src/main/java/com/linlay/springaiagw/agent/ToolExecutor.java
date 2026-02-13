package com.linlay.springaiagw.agent;

import com.aiagent.agw.sdk.model.ToolCallDelta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.springaiagw.model.agw.AgentDelta;
import com.linlay.springaiagw.service.FrontendSubmitCoordinator;
import com.linlay.springaiagw.service.LlmService;
import com.linlay.springaiagw.tool.BaseTool;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * 工具调用执行器：参数解析、调用、结果封装。
 */
class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver toolArgumentResolver;
    private final ObjectMapper objectMapper;
    private final AgentPromptBuilder promptBuilder;
    private final Map<String, BaseTool> enabledToolsByName;
    private final String agentId;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;

    ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentResolver toolArgumentResolver,
            ObjectMapper objectMapper,
            AgentPromptBuilder promptBuilder,
            Map<String, BaseTool> enabledToolsByName,
            String agentId,
            FrontendSubmitCoordinator frontendSubmitCoordinator
    ) {
        this.toolRegistry = toolRegistry;
        this.toolArgumentResolver = toolArgumentResolver;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.enabledToolsByName = enabledToolsByName;
        this.agentId = agentId;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
    }

    Flux<AgentDelta> executeTool(
            PlannedToolCall plannedCall,
            String callId,
            String runId,
            List<Map<String, Object>> records,
            boolean skipToolCallEmission
    ) {
        String normalizedToolName = DecisionChunkHandler.normalizeToolName(plannedCall.name());
        if (!enabledToolsByName.containsKey(normalizedToolName)) {
            return Flux.just(AgentDelta.toolResult(
                    callId,
                    errorResult(normalizedToolName, "Tool is not enabled for this agent: " + normalizedToolName)
            ));
        }

        Map<String, Object> args = new LinkedHashMap<>();
        if (plannedCall.arguments() != null) {
            args.putAll(plannedCall.arguments());
        }
        Map<String, Object> resolvedArgs = toolArgumentResolver.resolveToolArguments(normalizedToolName, args, records);
        String callType = toolRegistry.toolCallType(normalizedToolName);

        if (!resolvedArgs.equals(args)) {
            log.info("[agent:{}] resolved tool args callId={}, tool={}, planned={}, resolved={}",
                    agentId, callId, normalizedToolName, promptBuilder.toJson(args), promptBuilder.toJson(resolvedArgs));
        }

        Flux<AgentDelta> toolCallFlux;
        if (skipToolCallEmission) {
            toolCallFlux = Flux.empty();
        } else {
            String argsJson = promptBuilder.toJson(resolvedArgs);
            toolCallFlux = Flux.just(
                    AgentDelta.toolCalls(List.of(toolCall(callId, normalizedToolName, argsJson, callType)))
            );
        }

        Mono<AgentDelta> toolResultDelta = invokeByKind(runId, callId, normalizedToolName, callType, resolvedArgs)
                .map(result -> {
                    Map<String, Object> record = toolRecord(callId, normalizedToolName, callType, resolvedArgs, result);
                    records.add(record);
                    log.info("[agent:{}] tool finished callId={}, tool={}, record={}",
                            agentId, callId, normalizedToolName, promptBuilder.toJson(record));
                    return AgentDelta.toolResult(callId, result);
                })
                .onErrorResume(ex -> Mono.just(AgentDelta.toolResult(
                        callId,
                        errorResult(normalizedToolName, ex.getMessage())
                )));

        return Flux.concat(toolCallFlux, toolResultDelta.flux());
    }

    List<LlmService.LlmFunctionTool> enabledFunctionTools() {
        if (enabledToolsByName.isEmpty()) {
            return List.of();
        }
        return enabledToolsByName.values().stream()
                .sorted(Comparator.comparing(BaseTool::name))
                .map(tool -> new LlmService.LlmFunctionTool(
                        DecisionChunkHandler.normalizeToolName(tool.name()),
                        DecisionChunkHandler.normalize(tool.description(), ""),
                        tool.parametersSchema(),
                        false
                ))
                .toList();
    }

    List<PlannedToolCall> expandToolCallsForStep(PlannedStep step) {
        if (step == null || step.toolCall() == null) {
            return List.of();
        }
        PlannedToolCall toolCall = step.toolCall();
        if (!"bash".equals(DecisionChunkHandler.normalizeToolName(toolCall.name()))) {
            return List.of(toolCall);
        }
        if (toolCall.arguments() == null) {
            return List.of(toolCall);
        }
        Object rawCommand = toolCall.arguments().get("command");
        if (!(rawCommand instanceof String commandText)) {
            return List.of(toolCall);
        }
        List<String> splitCommands = splitBashCommands(commandText);
        if (splitCommands.size() <= 1) {
            return List.of(toolCall);
        }
        log.info("[agent:{}] split bash composite command in step '{}' to {} commands: {}",
                agentId,
                DecisionChunkHandler.normalize(step.step(), "unknown-step"),
                splitCommands.size(), splitCommands);

        List<PlannedToolCall> expanded = new ArrayList<>();
        for (String splitCommand : splitCommands) {
            Map<String, Object> splitArgs = new LinkedHashMap<>(toolCall.arguments());
            splitArgs.put("command", splitCommand);
            expanded.add(new PlannedToolCall(toolCall.name(), splitArgs, null));
        }
        return expanded;
    }

    List<String> splitBashCommands(String commandText) {
        if (commandText == null || commandText.isBlank()) {
            return List.of();
        }
        String[] parts = commandText.split("\\s*(?:&&|\\|\\||;|\\|)\\s*");
        List<String> commands = new ArrayList<>();
        for (String part : parts) {
            String normalized = DecisionChunkHandler.normalize(part, "").trim();
            if (!normalized.isBlank()) {
                commands.add(normalized);
            }
        }
        return commands;
    }

    Map<String, Object> toolRecord(String callId, String toolName, String toolType, Map<String, Object> args, JsonNode result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("callId", callId);
        record.put("toolName", toolName);
        record.put("toolType", toolType);
        record.put("arguments", args);
        record.put("result", result);
        return record;
    }

    private Mono<JsonNode> invokeByKind(
            String runId,
            String toolId,
            String toolName,
            String toolType,
            Map<String, Object> args
    ) {
        if ("action".equalsIgnoreCase(toolType)) {
            return Mono.just(objectMapper.getNodeFactory().textNode("OK"));
        }
        if (toolRegistry.isFrontend(toolName)) {
            if (frontendSubmitCoordinator == null) {
                return Mono.<JsonNode>just(errorResult(toolName, "Frontend submit coordinator is not configured"));
            }
            if (!StringUtils.hasText(runId)) {
                return Mono.<JsonNode>just(errorResult(toolName, "Missing runId for frontend tool submission"));
            }
            return frontendSubmitCoordinator.awaitSubmit(runId.trim(), toolId)
                    .<JsonNode>map(value -> {
                        Object payload = value == null ? Map.of() : value;
                        return objectMapper.valueToTree(payload);
                    })
                    .onErrorResume(ex -> Mono.<JsonNode>just(errorResult(toolName, ex.getMessage())));
        }
        return Mono.fromCallable(() -> safeInvoke(toolName, args))
                .subscribeOn(Schedulers.boundedElastic());
    }

    JsonNode safeInvoke(String toolName, Map<String, Object> args) {
        String normalizedName = DecisionChunkHandler.normalizeToolName(toolName);
        try {
            if (!enabledToolsByName.containsKey(normalizedName)) {
                return errorResult(normalizedName, "Tool is not enabled for this agent: " + normalizedName);
            }
            return toolRegistry.invoke(normalizedName, args);
        } catch (Exception ex) {
            return errorResult(normalizedName, ex.getMessage());
        }
    }

    private ObjectNode errorResult(String toolName, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("tool", toolName);
        error.put("ok", false);
        error.put("error", message == null ? "unknown error" : message);
        return error;
    }

    private ToolCallDelta toolCall(String callId, String toolName, String arguments, String toolType) {
        return new ToolCallDelta(callId, toolType, toolName, arguments);
    }
}
