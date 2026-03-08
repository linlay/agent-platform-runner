package com.linlay.agentplatform.service;

import com.linlay.agentplatform.stream.adapter.openai.OpenAiSseDeltaParser;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.AgentProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生 WebClient SSE 路径：直接构建 OpenAI Compatible HTTP 请求，解析 SSE delta。
 */
class OpenAiCompatibleSseClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleSseClient.class);
    private static final long DEFAULT_STREAM_TIMEOUT_MS = 60_000L;

    private final AgentProviderProperties providerProperties;
    private final ObjectMapper objectMapper;
    private final LlmCallLogger callLogger;
    private final OpenAiSseDeltaParser openAiSseDeltaParser;
    private final ConnectionProvider connectionProvider;

    OpenAiCompatibleSseClient(AgentProviderProperties providerProperties, ObjectMapper objectMapper,
                              LlmCallLogger callLogger, ConnectionProvider connectionProvider) {
        this.providerProperties = providerProperties;
        this.objectMapper = objectMapper;
        this.callLogger = callLogger;
        this.openAiSseDeltaParser = new OpenAiSseDeltaParser(objectMapper);
        this.connectionProvider = connectionProvider;
    }

    Flux<LlmDelta> streamDeltasRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            List<LlmService.LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            String jsonSchema,
            ComputePolicy computePolicy,
            boolean reasoningEnabled,
            Integer maxTokens,
            String traceId,
            String stage
    ) {
        return streamDeltasRawSse(
                providerKey,
                model,
                systemPrompt,
                historyMessages,
                userPrompt,
                tools,
                parallelToolCalls,
                toolChoice,
                jsonSchema,
                computePolicy,
                reasoningEnabled,
                maxTokens,
                traceId,
                stage,
                null
        );
    }

    Flux<LlmDelta> streamDeltasRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            List<LlmService.LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            String jsonSchema,
            ComputePolicy computePolicy,
            boolean reasoningEnabled,
            Integer maxTokens,
            String traceId,
            String stage,
            String endpointPath
    ) {
        return Flux.defer(() -> {
            AgentProviderProperties.ProviderConfig config = resolveProviderConfig(providerKey);
            WebClient webClient = buildRawWebClient(config);
            Map<String, Object> request = buildRawStreamRequest(
                    providerKey,
                    model,
                    systemPrompt,
                    historyMessages,
                    userPrompt,
                    tools,
                    parallelToolCalls,
                    toolChoice,
                    jsonSchema,
                    computePolicy,
                    reasoningEnabled,
                    maxTokens
            );
            boolean planStageRawLogging = isPlanGenerateStage(stage);
            AtomicInteger rawChunkIndex = new AtomicInteger(0);
            long rawStartNanos = System.nanoTime();

            callLogger.info(log, callLogger.message(traceId, stage, "LLM raw SSE delta stream request start provider={}, model={}, tools={}"),
                    providerKey, model, tools == null ? 0 : tools.size());

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);

            return webClient.post()
                    .uri(resolveRawCompletionsUri(config.getBaseUrl(), endpointPath))
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> firstChunkReceived.set(true))
                    .retryWhen(Retry.max(1)
                            .filter(ex -> !firstChunkReceived.get() && isConnectionError(ex)))
                    .doOnNext(rawChunk -> {
                        callLogger.debug(
                                log,
                                callLogger.message(traceId, stage, "raw", "{}"),
                                callLogger.sanitizeText(rawChunk)
                        );
                        if (planStageRawLogging) {
                            int chunk = rawChunkIndex.getAndIncrement();
                            long elapsedMs = elapsedMs(rawStartNanos);
                            callLogger.info(log, callLogger.message(traceId, stage, "raw-plan", "[chunkIndex={}][elapsedMs={}] {}"),
                                    chunk, elapsedMs, rawChunk);
                        }
                    })
                    .handle((rawChunk, sink) -> {
                        LlmDelta delta = openAiSseDeltaParser.parseOrNull(rawChunk);
                        if (delta != null) {
                            sink.next(delta);
                        }
                    });
        });
    }

    Flux<String> streamContentRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            String stage
    ) {
        return streamContentRawSse(providerKey, model, systemPrompt, historyMessages, userPrompt, stage, null);
    }

    Flux<String> streamContentRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            String stage,
            String endpointPath
    ) {
        return Flux.defer(() -> {
            String traceId = callLogger.generateTraceId();
            long startNanos = System.nanoTime();
            StringBuilder responseBuffer = new StringBuilder();

            callLogger.info(log, callLogger.message(traceId, stage, "LLM raw SSE content stream request start provider={}, model={}"), providerKey, model);
            callLogger.info(log, callLogger.message(traceId, stage, "LLM raw SSE content stream system prompt:\n{}"), callLogger.normalizePrompt(systemPrompt));
            callLogger.info(log, callLogger.message(traceId, stage, "LLM raw SSE content stream history messages count={}"), historyMessages == null ? 0 : historyMessages.size());
            callLogger.logHistoryMessages(log, traceId, stage, historyMessages);
            callLogger.info(log, callLogger.message(traceId, stage, "LLM raw SSE content stream user prompt:\n{}"), callLogger.normalizePrompt(userPrompt));

            AgentProviderProperties.ProviderConfig config = resolveProviderConfig(providerKey);
            WebClient webClient = buildRawWebClient(config);
            Map<String, Object> request = buildRawStreamRequest(
                    providerKey,
                    model,
                    systemPrompt,
                    historyMessages,
                    userPrompt,
                    List.of(),
                    false,
                    ToolChoice.NONE,
                    null,
                    ComputePolicy.MEDIUM,
                    false,
                    null
            );
            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            return webClient.post()
                    .uri(resolveRawCompletionsUri(config.getBaseUrl(), endpointPath))
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> firstChunkReceived.set(true))
                    .retryWhen(Retry.max(1)
                            .filter(ex -> !firstChunkReceived.get() && isConnectionError(ex)))
                    .<String>handle((rawChunk, sink) -> {
                        LlmDelta delta = openAiSseDeltaParser.parseOrNull(rawChunk);
                        if (delta != null && delta.content() != null && !delta.content().isEmpty()) {
                            sink.next(delta.content());
                        }
                    })
                    .doOnNext(chunk -> {
                        String safeChunk = callLogger.sanitizeText(chunk);
                        responseBuffer.append(safeChunk);
                        callLogger.debug(log, callLogger.message(traceId, stage, "delta", "content: {}"), safeChunk);
                    })
                    .doOnComplete(() -> callLogger.info(
                            log,
                            callLogger.message(traceId, stage, "LLM raw SSE content stream finished in {} ms:\n{}"),
                            elapsedMs(startNanos), responseBuffer
                    ))
                    .doOnError(ex -> log.error(callLogger.message(traceId, stage, "LLM raw SSE content stream failed in {} ms, partial response:\n{}"),
                            elapsedMs(startNanos), responseBuffer, ex))
                    .doOnCancel(() -> callLogger.info(
                            log,
                            callLogger.message(traceId, stage, "LLM raw SSE content stream canceled in {} ms, partial response:\n{}"),
                            elapsedMs(startNanos), responseBuffer
                    ))
                    .timeout(Duration.ofMillis(DEFAULT_STREAM_TIMEOUT_MS));
        });
    }

    // --- private helpers ---

    private AgentProviderProperties.ProviderConfig resolveProviderConfig(String providerKey) {
        if (providerProperties == null) {
            throw new IllegalStateException("Provider properties not configured");
        }
        AgentProviderProperties.ProviderConfig config = providerProperties.getProvider(providerKey);
        if (config == null) {
            throw new IllegalStateException("No provider config found for key: " + providerKey);
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new IllegalStateException("Missing base-url for key: " + providerKey);
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new IllegalStateException("Missing api-key for key: " + providerKey);
        }
        return config;
    }

    private WebClient buildRawWebClient(AgentProviderProperties.ProviderConfig config) {
        if (config == null) {
            throw new IllegalStateException("Provider config not found");
        }
        HttpClient httpClient = connectionProvider != null
                ? HttpClient.create(connectionProvider)
                : HttpClient.create();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String resolveRawCompletionsUri(String baseUrl, String endpointPath) {
        if (StringUtils.hasText(endpointPath)) {
            String path = endpointPath.trim();
            return path.startsWith("/") ? path : "/" + path;
        }
        String normalized = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/v1") || normalized.endsWith("/v1/")) {
            return "/chat/completions";
        }
        return "/v1/chat/completions";
    }

    Map<String, Object> buildRequestBody(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            List<LlmService.LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            String jsonSchema,
            ComputePolicy computePolicy,
            boolean reasoningEnabled,
            Integer maxTokens
    ) {
        return buildRawStreamRequest(
                providerKey,
                model,
                systemPrompt,
                historyMessages,
                userPrompt,
                tools,
                parallelToolCalls,
                toolChoice,
                jsonSchema,
                computePolicy,
                reasoningEnabled,
                maxTokens
        );
    }

    private Map<String, Object> buildRawStreamRequest(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            List<LlmService.LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            String jsonSchema,
            ComputePolicy computePolicy,
            boolean reasoningEnabled,
            Integer maxTokens
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        request.put("messages", buildRawMessages(systemPrompt, historyMessages, userPrompt));

        if (maxTokens != null && maxTokens > 0) {
            request.put("max_tokens", maxTokens);
        }

        if (isBailianProvider(providerKey)) {
            request.put("enable_thinking", reasoningEnabled);
        }

        if (reasoningEnabled) {
            request.put("reasoning", Map.of("effort", toReasoningEffort(computePolicy)));
        }

        if (jsonSchema != null && !jsonSchema.isBlank()) {
            request.put("response_format", buildJsonSchemaFormat(jsonSchema));
        }

        List<Map<String, Object>> rawTools = buildRawTools(tools);
        if (!rawTools.isEmpty() && toolChoice != ToolChoice.NONE) {
            request.put("tools", rawTools);
            request.put("tool_choice", toToolChoiceValue(toolChoice));
            request.put("parallel_tool_calls", parallelToolCalls);
        }
        return request;
    }

    private Map<String, Object> buildJsonSchemaFormat(String schema) {
        try {
            Object schemaNode = objectMapper.readValue(schema, Object.class);
            return Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "response_schema",
                            "schema", schemaNode,
                            "strict", true
                    )
            );
        } catch (Exception ignored) {
            return Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "response_schema",
                            "schema", Map.of("type", "object"),
                            "strict", false
                    )
            );
        }
    }

    private String toReasoningEffort(ComputePolicy computePolicy) {
        if (computePolicy == null) {
            return "medium";
        }
        return switch (computePolicy) {
            case LOW -> "low";
            case HIGH -> "high";
            case MEDIUM -> "medium";
        };
    }

    private String toToolChoiceValue(ToolChoice toolChoice) {
        if (toolChoice == null) {
            return "auto";
        }
        return switch (toolChoice) {
            case NONE -> "none";
            case REQUIRED -> "required";
            case AUTO -> "auto";
        };
    }

    private List<Map<String, Object>> buildRawMessages(
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(rawTextMessage("system", systemPrompt));
        }
        if (historyMessages != null && !historyMessages.isEmpty()) {
            for (ChatMessage message : historyMessages) {
                messages.addAll(toRawMessages(message));
            }
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            messages.add(rawTextMessage("user", userPrompt));
        }
        return messages;
    }

    private List<Map<String, Object>> toRawMessages(ChatMessage message) {
        if (message == null) {
            return List.of();
        }
        if (message instanceof ChatMessage.SystemMsg systemMsg) {
            return List.of(rawTextMessage("system", systemMsg.text()));
        }
        if (message instanceof ChatMessage.UserMsg userMsg) {
            return List.of(rawTextMessage("user", userMsg.text()));
        }
        if (message instanceof ChatMessage.AssistantMsg assistantMsg) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            String content = assistantMsg.text();
            assistant.put("content", content == null ? "" : content);
            if (assistantMsg.toolCalls() != null && !assistantMsg.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ChatMessage.AssistantMsg.ToolCall call : assistantMsg.toolCalls()) {
                    if (call == null) {
                        continue;
                    }
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", call.id());
                    toolCall.put("type", call.type() == null ? "function" : call.type());
                    toolCall.put("function", function);
                    toolCalls.add(toolCall);
                }
                if (!toolCalls.isEmpty()) {
                    assistant.put("tool_calls", toolCalls);
                }
            }
            return List.of(assistant);
        }
        if (message instanceof ChatMessage.ToolResultMsg toolResultMsg) {
            if (toolResultMsg.responses() == null || toolResultMsg.responses().isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> toolMessages = new ArrayList<>();
            for (ChatMessage.ToolResultMsg.ToolResponse response : toolResultMsg.responses()) {
                if (response == null) {
                    continue;
                }
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("role", "tool");
                tool.put("tool_call_id", response.id());
                tool.put("name", response.name());
                tool.put("content", response.responseData());
                toolMessages.add(tool);
            }
            return toolMessages;
        }

        return List.of(rawTextMessage(message.role(), message.text()));
    }

    private Map<String, Object> rawTextMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private List<Map<String, Object>> buildRawTools(List<LlmService.LlmFunctionTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rawTools = new ArrayList<>();
        for (LlmService.LlmFunctionTool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            if (tool.description() != null && !tool.description().isBlank()) {
                function.put("description", tool.description());
            }
            function.put("parameters", tool.parameters() == null ? Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "additionalProperties", true
            ) : tool.parameters());
            if (tool.strict() != null) {
                function.put("strict", tool.strict());
            }
            Map<String, Object> toolMap = new LinkedHashMap<>();
            toolMap.put("type", "function");
            toolMap.put("function", function);
            rawTools.add(toolMap);
        }
        return rawTools;
    }

    private boolean isBailianProvider(String providerKey) {
        return providerKey != null && "bailian".equalsIgnoreCase(providerKey.trim());
    }

    private String safeJson(Object value) {
        try {
            return callLogger.sanitizeText(objectMapper.writeValueAsString(value));
        } catch (Exception ex) {
            return callLogger.sanitizeText(String.valueOf(value));
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private boolean isPlanGenerateStage(String stage) {
        return "agent-plan-generate".equals(stage);
    }

    private boolean isConnectionError(Throwable ex) {
        if (ex instanceof IOException) {
            return true;
        }
        Throwable cause = ex.getCause();
        return cause instanceof IOException;
    }
}
