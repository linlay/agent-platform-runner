package com.linlay.agentplatform.service;

import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.AgentProviderProperties;
import com.linlay.agentplatform.config.ChatClientRegistry;
import com.linlay.agentplatform.config.LlmInteractionLogProperties;
import com.linlay.agentplatform.model.ModelProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final long DEFAULT_STREAM_TIMEOUT_MS = 60_000L;

    private final ChatClientRegistry chatClientRegistry;
    private final AgentProviderProperties providerProperties;
    private final OpenAiCompatibleSseClient openAiCompatibleSseClient;
    private final NewApiOpenAiCompatibleSseClient newApiOpenAiCompatibleSseClient;
    private final AnthropicSseClient anthropicSseClient;
    private final LlmCallLogger callLogger;

    public record LlmFunctionTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Boolean strict
    ) {
    }

    public LlmService(ChatClientRegistry chatClientRegistry) {
        this(
                chatClientRegistry,
                new AgentProviderProperties(),
                new ObjectMapper(),
                new LlmInteractionLogProperties(),
                null
        );
    }

    @Autowired
    public LlmService(
            ChatClientRegistry chatClientRegistry,
            AgentProviderProperties providerProperties,
            ObjectMapper objectMapper,
            LlmInteractionLogProperties logProperties,
            ConnectionProvider llmConnectionProvider
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.providerProperties = providerProperties == null ? new AgentProviderProperties() : providerProperties;
        this.callLogger = new LlmCallLogger(logProperties);
        this.openAiCompatibleSseClient = new OpenAiCompatibleSseClient(this.providerProperties, objectMapper, this.callLogger, llmConnectionProvider);
        this.newApiOpenAiCompatibleSseClient = new NewApiOpenAiCompatibleSseClient(this.providerProperties, this.openAiCompatibleSseClient);
        this.anthropicSseClient = new AnthropicSseClient();
    }

    public Flux<String> streamContent(LlmCallSpec spec) {
        if (spec == null) {
            return Flux.error(new IllegalArgumentException("spec must not be null"));
        }
        return streamContentInternal(spec)
                .filter(StringUtils::hasText);
    }

    public Flux<LlmDelta> streamDeltas(LlmCallSpec spec) {
        if (spec == null) {
            return Flux.error(new IllegalArgumentException("spec must not be null"));
        }
        return streamDeltasInternal(spec);
    }

    public Flux<String> streamContentRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<Message> historyMessages,
            String userPrompt,
            String stage
    ) {
        return streamContentRawSse(
                providerKey,
                model,
                ModelProtocol.OPENAI,
                systemPrompt,
                historyMessages,
                userPrompt,
                stage
        );
    }

    public Flux<String> streamContentRawSse(
            String providerKey,
            String model,
            ModelProtocol protocol,
            String systemPrompt,
            List<Message> historyMessages,
            String userPrompt,
            String stage
    ) {
        if (protocol == ModelProtocol.ANTHROPIC) {
            return anthropicSseClient.streamContentRawSse(providerKey, model, systemPrompt, historyMessages, userPrompt, stage);
        }
        if (protocol == ModelProtocol.NEWAPI_OPENAI_COMPATIBLE) {
            return newApiOpenAiCompatibleSseClient.streamContentRawSse(
                    providerKey,
                    model,
                    systemPrompt,
                    historyMessages,
                    userPrompt,
                    stage
            );
        }
        return openAiCompatibleSseClient.streamContentRawSse(providerKey, model, systemPrompt, historyMessages, userPrompt, stage);
    }

    public Mono<String> completeText(String providerKey, String model, String systemPrompt, String userPrompt) {
        return completeText(providerKey, model, systemPrompt, userPrompt, "default");
    }

    public Mono<String> completeText(
            String providerKey,
            String model,
            String systemPrompt,
            String userPrompt,
            String stage
    ) {
        return completeTextInternal(providerKey, model, ModelProtocol.OPENAI, systemPrompt, userPrompt, stage);
    }

    private Flux<String> streamContentInternal(LlmCallSpec spec) {
        if (spec.protocol() == ModelProtocol.ANTHROPIC) {
            return anthropicSseClient.streamContentRawSse(
                    spec.providerKey(),
                    spec.model(),
                    spec.systemPrompt(),
                    spec.messages(),
                    spec.userPrompt(),
                    spec.stage()
            );
        }
        if (spec.protocol() == ModelProtocol.NEWAPI_OPENAI_COMPATIBLE) {
            return newApiOpenAiCompatibleSseClient.streamContentRawSse(
                    spec.providerKey(),
                    spec.model(),
                    spec.systemPrompt(),
                    spec.messages(),
                    spec.userPrompt(),
                    spec.stage()
            );
        }
        ChatClient chatClient = resolveChatClient(spec.providerKey());
        if (chatClient == null) {
            return Flux.error(new IllegalStateException("No ChatClient registered for provider key: " + spec.providerKey()));
        }

        return Flux.defer(() -> {
            String traceId = callLogger.generateTraceId();
            long startNanos = System.nanoTime();
            StringBuilder responseBuffer = new StringBuilder();

            callLogger.info(log, "[{}][{}] LLM stream request start provider={}, model={}", traceId, spec.stage(), spec.providerKey(), spec.model());
            callLogger.info(log, "[{}][{}] LLM stream request body:\n{}", traceId, spec.stage(), safeJson(openAiCompatibleSseClient.buildRequestBody(
                    spec.providerKey(),
                    spec.model(),
                    spec.systemPrompt(),
                    spec.messages(),
                    spec.userPrompt(),
                    List.of(),
                    false,
                    ToolChoice.NONE,
                    null,
                    ComputePolicy.MEDIUM,
                    false,
                    null
            )));
            callLogger.info(log, "[{}][{}] LLM stream system prompt:\n{}", traceId, spec.stage(), callLogger.normalizePrompt(spec.systemPrompt()));
            callLogger.info(log, "[{}][{}] LLM stream history messages count={}", traceId, spec.stage(), spec.messages().size());
            callLogger.logHistoryMessages(log, traceId, spec.stage(), spec.messages());
            callLogger.info(log, "[{}][{}] LLM stream user prompt:\n{}", traceId, spec.stage(), callLogger.normalizePrompt(spec.userPrompt()));

            OpenAiChatOptions options = OpenAiChatOptions.builder().model(spec.model()).build();
            ChatClient.ChatClientRequestSpec prompt = chatClient.prompt().options(options);
            if (StringUtils.hasText(spec.systemPrompt())) {
                prompt = prompt.system(spec.systemPrompt());
            }
            if (!spec.messages().isEmpty()) {
                prompt = prompt.messages(spec.messages());
            }

            return prompt.user(spec.userPrompt())
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        if (chunk != null) {
                            String safeChunk = callLogger.sanitizeText(chunk);
                            responseBuffer.append(safeChunk);
                            callLogger.debug(log, "[{}][{}][delta] content: {}", traceId, spec.stage(), safeChunk);
                        }
                    })
                    .doOnComplete(() -> callLogger.info(
                            log,
                            "[{}][{}] LLM stream response finished in {} ms:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer
                    ))
                    .doOnError(ex -> log.error(
                            "[{}][{}] LLM stream failed in {} ms, partial response:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer,
                            ex
                    ))
                    .doOnCancel(() -> callLogger.info(
                            log,
                            "[{}][{}] LLM stream canceled in {} ms, partial response:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer
                    ))
                    .timeout(Duration.ofMillis(resolveTimeoutMs(spec.timeoutMs())));
        });
    }

    private Flux<LlmDelta> streamDeltasInternal(LlmCallSpec spec) {
        return Flux.defer(() -> {
            String traceId = callLogger.generateTraceId();
            long startNanos = System.nanoTime();
            StringBuilder responseBuffer = new StringBuilder();
            ChatClient chatClient = resolveChatClient(spec.providerKey());
            boolean hasTools = !spec.tools().isEmpty();
            boolean preferRawSse = requiresRawSse(spec.jsonSchema(), spec.compute(), spec.reasoningEnabled())
                    || spec.protocol() == ModelProtocol.NEWAPI_OPENAI_COMPATIBLE;
            Map<String, Object> requestBody = openAiCompatibleSseClient.buildRequestBody(
                    spec.providerKey(),
                    spec.model(),
                    spec.systemPrompt(),
                    spec.messages(),
                    spec.userPrompt(),
                    spec.tools(),
                    spec.parallelToolCalls(),
                    spec.toolChoice(),
                    spec.jsonSchema(),
                    spec.compute(),
                    spec.reasoningEnabled(),
                    spec.maxTokens()
            );

            callLogger.info(log, "[{}][{}] LLM delta stream request start provider={}, model={}, tools={}",
                    traceId, spec.stage(), spec.providerKey(), spec.model(), hasTools ? spec.tools().size() : 0);
            callLogger.info(log, "[{}][{}] LLM delta stream request body:\n{}",
                    traceId, spec.stage(), safeJson(requestBody));
            callLogger.info(log, "[{}][{}] LLM delta stream system prompt:\n{}", traceId, spec.stage(), callLogger.normalizePrompt(spec.systemPrompt()));
            callLogger.info(log, "[{}][{}] LLM delta stream history messages count={}", traceId, spec.stage(), spec.messages().size());
            callLogger.logHistoryMessages(log, traceId, spec.stage(), spec.messages());
            callLogger.info(log, "[{}][{}] LLM delta stream user prompt:\n{}", traceId, spec.stage(), callLogger.normalizePrompt(spec.userPrompt()));

            if (chatClient == null) {
                return Flux.error(new IllegalStateException("No ChatClient registered for provider key: " + spec.providerKey()));
            }

            Flux<LlmDelta> deltaFlux;
            if (spec.protocol() == ModelProtocol.ANTHROPIC) {
                deltaFlux = anthropicSseClient.streamDeltasRawSse(
                        spec.providerKey(),
                        spec.model(),
                        spec.systemPrompt(),
                        spec.messages(),
                        spec.userPrompt(),
                        spec.tools(),
                        spec.parallelToolCalls(),
                        spec.toolChoice(),
                        spec.jsonSchema(),
                        spec.compute(),
                        spec.reasoningEnabled(),
                        spec.maxTokens(),
                        traceId,
                        spec.stage()
                );
            } else if (spec.protocol() == ModelProtocol.NEWAPI_OPENAI_COMPATIBLE) {
                deltaFlux = newApiOpenAiCompatibleSseClient.streamDeltasRawSse(
                        spec.providerKey(),
                        spec.model(),
                        spec.systemPrompt(),
                        spec.messages(),
                        spec.userPrompt(),
                        spec.tools(),
                        spec.parallelToolCalls(),
                        spec.toolChoice(),
                        spec.jsonSchema(),
                        spec.compute(),
                        spec.reasoningEnabled(),
                        spec.maxTokens(),
                        traceId,
                        spec.stage()
                );
            } else if (hasTools || preferRawSse) {
                AtomicBoolean rawDeltaEmitted = new AtomicBoolean(false);
                deltaFlux = openAiCompatibleSseClient.streamDeltasRawSse(
                                spec.providerKey(),
                                spec.model(),
                                spec.systemPrompt(),
                                spec.messages(),
                                spec.userPrompt(),
                                spec.tools(),
                                spec.parallelToolCalls(),
                                spec.toolChoice(),
                                spec.jsonSchema(),
                                spec.compute(),
                                spec.reasoningEnabled(),
                                spec.maxTokens(),
                                traceId,
                                spec.stage()
                        )
                        .doOnNext(ignored -> rawDeltaEmitted.set(true))
                        .onErrorResume(ex -> {
                            if (rawDeltaEmitted.get()) {
                                log.warn(
                                        "[{}][{}] raw delta stream failed after partial output, skip fallback to avoid duplicate stream events",
                                        traceId,
                                        spec.stage(),
                                        ex
                                );
                                return Flux.error(ex);
                            }
                            log.warn("[{}][{}] raw delta stream failed, fallback to ChatClient stream", traceId, spec.stage(), ex);
                            return streamDeltasByChatClient(
                                    chatClient,
                                    spec.model(),
                                    spec.systemPrompt(),
                                    spec.messages(),
                                    spec.userPrompt(),
                                    spec.tools(),
                                    spec.parallelToolCalls(),
                                    spec.toolChoice(),
                                    spec.maxTokens()
                            );
                        });
            } else {
                deltaFlux = streamDeltasByChatClient(
                        chatClient,
                        spec.model(),
                        spec.systemPrompt(),
                        spec.messages(),
                        spec.userPrompt(),
                        spec.tools(),
                        false,
                        spec.toolChoice(),
                        spec.maxTokens()
                );
            }

            return deltaFlux
                    .filter(delta -> delta != null
                            && (StringUtils.hasText(delta.reasoning())
                            || (StringUtils.hasText(delta.content()))
                            || (delta.toolCalls() != null && !delta.toolCalls().isEmpty())
                            || StringUtils.hasText(delta.finishReason())
                            || (delta.usage() != null && !delta.usage().isEmpty())))
                    .doOnNext(delta -> callLogger.appendDeltaLog(responseBuffer, delta, traceId, spec.stage()))
                    .doOnComplete(() -> callLogger.info(
                            log,
                            "[{}][{}] LLM delta stream response finished in {} ms:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer
                    ))
                    .doOnError(ex -> log.error(
                            "[{}][{}] LLM delta stream failed in {} ms, partial response:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer,
                            ex
                    ))
                    .doOnCancel(() -> callLogger.info(
                            log,
                            "[{}][{}] LLM delta stream canceled in {} ms, partial response:\n{}",
                            traceId,
                            spec.stage(),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer
                    ))
                    .timeout(Duration.ofMillis(resolveTimeoutMs(spec.timeoutMs())));
        });
    }

    private long resolveTimeoutMs(Long timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return DEFAULT_STREAM_TIMEOUT_MS;
        }
        return timeoutMs;
    }

    private Mono<String> completeTextInternal(
            String providerKey,
            String model,
            ModelProtocol protocol,
            String systemPrompt,
            String userPrompt,
            String stage
    ) {
        if (protocol == ModelProtocol.ANTHROPIC) {
            return Mono.error(anthropicSseClient.notImplemented(providerKey));
        }
        if (protocol == ModelProtocol.NEWAPI_OPENAI_COMPATIBLE) {
            return streamContentRawSse(
                    providerKey,
                    model,
                    protocol,
                    systemPrompt,
                    List.of(),
                    userPrompt,
                    stage
            ).reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString);
        }
        return Mono.fromCallable(() -> {
                    ChatClient chatClient = resolveChatClient(providerKey);
                    if (chatClient == null) {
                        throw new IllegalStateException("No ChatClient registered for provider key: " + providerKey);
                    }
                    String traceId = callLogger.generateTraceId();
                    long startNanos = System.nanoTime();

                    callLogger.info(log, "[{}][{}] LLM call request start provider={}, model={}", traceId, stage, providerKey, model);
                    callLogger.info(log, "[{}][{}] LLM call request body:\n{}", traceId, stage, safeJson(openAiCompatibleSseClient.buildRequestBody(
                            providerKey,
                            model,
                            systemPrompt,
                            List.of(),
                            userPrompt,
                            List.of(),
                            false,
                            ToolChoice.NONE,
                            null,
                            ComputePolicy.MEDIUM,
                            false,
                            null
                    )));
                    callLogger.info(log, "[{}][{}] LLM call system prompt:\n{}", traceId, stage, callLogger.normalizePrompt(systemPrompt));
                    callLogger.info(log, "[{}][{}] LLM call user prompt:\n{}", traceId, stage, callLogger.normalizePrompt(userPrompt));

                    OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
                    ChatClient.ChatClientRequestSpec prompt = chatClient.prompt().options(options);
                    if (StringUtils.hasText(systemPrompt)) {
                        prompt = prompt.system(systemPrompt);
                    }

                    String content = prompt.user(userPrompt)
                            .call()
                            .content();
                    String normalized = content == null ? "" : content;

                    callLogger.info(
                            log,
                            "[{}][{}] LLM call response finished in {} ms:\n{}",
                            traceId,
                            stage,
                            callLogger.elapsedMs(startNanos),
                            callLogger.sanitizeText(normalized)
                    );
                    return normalized;
                })
                .doOnError(ex -> log.error("LLM call failed provider={}, stage={}", providerKey, stage, ex))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn("");
    }

    private Flux<LlmDelta> streamDeltasByChatClient(
            ChatClient chatClient,
            String model,
            String systemPrompt,
            List<Message> historyMessages,
            String userPrompt,
            List<LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            Integer maxTokens
    ) {
        OpenAiChatOptions options = buildStreamOptions(model, tools, parallelToolCalls, toolChoice, maxTokens);
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt().options(options);
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        if (historyMessages != null && !historyMessages.isEmpty()) {
            prompt = prompt.messages(historyMessages);
        }
        if (StringUtils.hasText(userPrompt)) {
            prompt = prompt.user(userPrompt);
        }

        return prompt.stream()
                .chatResponse()
                .map(this::toStreamDelta);
    }

    private OpenAiChatOptions buildStreamOptions(
            String model,
            List<LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            Integer maxTokens
    ) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                // We consume native tool_calls ourselves. Disable Spring AI internal execution.
                .internalToolExecutionEnabled(false);
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        if (tools != null && !tools.isEmpty() && toolChoice != ToolChoice.NONE) {
            List<OpenAiApi.FunctionTool> functionTools = tools.stream()
                    .filter(tool -> tool != null && StringUtils.hasText(tool.name()))
                    .map(this::toOpenAiFunctionTool)
                    .toList();
            if (!functionTools.isEmpty()) {
                builder.tools(functionTools);
                builder.toolChoice(toToolChoiceValue(toolChoice));
                builder.parallelToolCalls(parallelToolCalls);
            }
        }
        return builder.build();
    }

    private boolean requiresRawSse(String jsonSchema, ComputePolicy computePolicy, boolean reasoningEnabled) {
        if (StringUtils.hasText(jsonSchema)) {
            return true;
        }
        if (reasoningEnabled) {
            return true;
        }
        return computePolicy != null && computePolicy != ComputePolicy.MEDIUM;
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

    private OpenAiApi.FunctionTool toOpenAiFunctionTool(LlmFunctionTool tool) {
        Map<String, Object> parameters = tool.parameters() == null ? Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", true
        ) : tool.parameters();
        OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
                tool.description(),
                tool.name(),
                parameters,
                tool.strict()
        );
        return new OpenAiApi.FunctionTool(function);
    }

    private LlmDelta toStreamDelta(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new LlmDelta(null, null, null);
        }

        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText();
        List<ToolCallDelta> toolCalls = output.getToolCalls() == null ? List.of()
                : output.getToolCalls().stream()
                .map(call -> new ToolCallDelta(
                        call.id(),
                        call.type(),
                        call.name(),
                        call.arguments()
                ))
                .toList();
        String finishReason = response.getResult().getMetadata() == null
                ? null
                : response.getResult().getMetadata().getFinishReason();
        return new LlmDelta(content, toolCalls.isEmpty() ? null : toolCalls, finishReason);
    }

    private ChatClient resolveChatClient(String providerKey) {
        if (chatClientRegistry == null) {
            return null;
        }
        return chatClientRegistry.getClient(providerKey);
    }

    private String safeJson(Object value) {
        try {
            return callLogger.sanitizeText(new ObjectMapper().writeValueAsString(value));
        } catch (Exception ex) {
            return callLogger.sanitizeText(String.valueOf(value));
        }
    }

}
