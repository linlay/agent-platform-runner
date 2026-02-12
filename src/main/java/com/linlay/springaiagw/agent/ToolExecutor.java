package com.linlay.springaiagw.agent;

import com.aiagent.agw.sdk.model.ToolCallDelta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.springaiagw.model.agw.AgentDelta;
import com.linlay.springaiagw.service.LlmService;
import com.linlay.springaiagw.tool.BaseTool;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentResolver toolArgumentResolver,
            ObjectMapper objectMapper,
            AgentPromptBuilder promptBuilder,
            Map<String, BaseTool> enabledToolsByName,
            String agentId
    ) {
        this.toolRegistry = toolRegistry;
        this.toolArgumentResolver = toolArgumentResolver;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.enabledToolsByName = enabledToolsByName;
        this.agentId = agentId;
    }
    Flux<AgentDelta> executeTool(
            PlannedToolCall plannedCall, String callId,
            List<Map<String, Object>> records,
            boolean skipToolCallEmission
    ) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (plannedCall.arguments() != null) {
            args.putAll(plannedCall.arguments());
        }
        Map<String, Object> resolvedArgs = toolArgumentResolver.resolveToolArguments(plannedCall.name(), args, records);

        if (!resolvedArgs.equals(args)) {
            log.info("[agent:{}] resolved tool args callId={}, tool={}, planned={}, resolved={}",
                    agentId, callId, plannedCall.name(), promptBuilder.toJson(args), promptBuilder.toJson(resolvedArgs));
        }

        Flux<AgentDelta> toolCallFlux;
        if (skipToolCallEmission) {
            toolCallFlux = Flux.empty();
        } else {
            String argsJson = promptBuilder.toJson(resolvedArgs);
            toolCallFlux = Flux.just(
                    AgentDelta.toolCalls(List.of(toolCall(callId, plannedCall.name(), argsJson)))
            );
        }

        Mono<AgentDelta> toolResultDelta = Mono.fromCallable(() -> {
                    JsonNode result = safeInvoke(plannedCall.name(), resolvedArgs);
                    Map<String, Object> record = toolRecord(callId, plannedCall.name(), resolvedArgs, result);
                    records.add(record);
                    log.info("[agent:{}] tool finished callId={}, tool={}, record={}",
                            agentId, callId, plannedCall.name(), promptBuilder.toJson(record));
                    return AgentDelta.toolResult(callId, result);
                })
                .subscribeOn(Schedulers.boundedElastic());

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

    Map<String, Object> toolRecord(String callId, String toolName, Map<String, Object> args, JsonNode result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("callId", callId);
        record.put("toolName", toolName);
        record.put("arguments", args);
        record.put("result", result);
        return record;
    }

    JsonNode safeInvoke(String toolName, Map<String, Object> args) {
        String normalizedName = DecisionChunkHandler.normalizeToolName(toolName);
        try {
            if (!enabledToolsByName.containsKey(normalizedName)) {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("tool", normalizedName);
                error.put("ok", false);
                error.put("error", "Tool is not enabled for this agent: " + normalizedName);
                return error;
            }
            return toolRegistry.invoke(normalizedName, args);
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("tool", normalizedName);
            error.put("ok", false);
            error.put("error", ex.getMessage());
            return error;
        }
    }

    private ToolCallDelta toolCall(String callId, String toolName, String arguments) {
        return new ToolCallDelta(callId, "function", toolName, arguments);
    }
}
