package com.linlay.agentplatform.service.llm;

import com.linlay.agentplatform.stream.model.LlmDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.LlmInteractionLogProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.model.ModelProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final long DEFAULT_STREAM_TIMEOUT_MS = 60_000L;

    private final OpenAiCompatibleSseClient openAiCompatibleSseClient;
    private final LlmCallLogger callLogger;

    public record LlmFunctionTool(
            String name,
            String description,
            Map<String, Object> parameters,
            Boolean strict
    ) {
    }

    public LlmService() {
        this(
                emptyProviderRegistryService(),
                new ObjectMapper(),
                new LlmInteractionLogProperties(),
                null
        );
    }

    @Autowired
    public LlmService(
            ProviderRegistryService providerRegistryService,
            ObjectMapper objectMapper,
            LlmInteractionLogProperties logProperties,
            ConnectionProvider llmConnectionProvider
    ) {
        this.callLogger = new LlmCallLogger(logProperties);
        this.openAiCompatibleSseClient = new OpenAiCompatibleSseClient(providerRegistryService, objectMapper, this.callLogger, llmConnectionProvider);
    }

    private static ProviderRegistryService emptyProviderRegistryService() {
        ProviderProperties providerProperties = new ProviderProperties();
        providerProperties.setExternalDir("__missing_providers__");
        return new ProviderRegistryService(providerProperties);
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
            List<ChatMessage> historyMessages,
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
            List<ChatMessage> historyMessages,
            String userPrompt,
            String stage
    ) {
        return openAiCompatibleSseClient.streamContentRawSse(
                providerKey,
                model,
                protocol,
                systemPrompt,
                historyMessages,
                userPrompt,
                stage
        );
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
        return openAiCompatibleSseClient.streamContentRawSse(
                spec.providerKey(),
                spec.model(),
                spec.protocol(),
                spec.systemPrompt(),
                spec.messages(),
                spec.userPrompt(),
                spec.stage()
        );
    }

    private Flux<LlmDelta> streamDeltasInternal(LlmCallSpec spec) {
        return Flux.defer(() -> {
            String traceId = callLogger.generateTraceId();
            long startNanos = System.nanoTime();
            StringBuilder responseBuffer = new StringBuilder();
            boolean hasTools = !spec.tools().isEmpty();
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

            callLogger.info(log, callLogger.message(traceId, spec.stage(), "LLM delta stream request start provider={}, model={}, tools={}"),
                    spec.providerKey(), spec.model(), hasTools ? spec.tools().size() : 0);
            callLogger.info(log, callLogger.message(traceId, spec.stage(), "LLM delta stream request body:\n{}"),
                    safeJson(requestBody));
            callLogger.info(log, callLogger.message(traceId, spec.stage(), "LLM delta stream system prompt:\n{}"), callLogger.normalizePrompt(spec.systemPrompt()));
            callLogger.info(log, callLogger.message(traceId, spec.stage(), "LLM delta stream history messages count={}"), spec.messages().size());
            callLogger.logHistoryMessages(log, traceId, spec.stage(), spec.messages());
            callLogger.info(log, callLogger.message(traceId, spec.stage(), "LLM delta stream user prompt:\n{}"), callLogger.normalizePrompt(spec.userPrompt()));

            Flux<LlmDelta> deltaFlux = openAiCompatibleSseClient.streamDeltasRawSse(
                    spec.providerKey(),
                    spec.model(),
                    spec.protocol(),
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

            Flux<LlmDelta> cancelAwareFlux = deltaFlux.takeUntilOther(spec.cancelSignal());
            return cancelAwareFlux
                    .filter(delta -> delta != null
                            && (StringUtils.hasText(delta.reasoning())
                            || (StringUtils.hasText(delta.content()))
                            || (delta.toolCalls() != null && !delta.toolCalls().isEmpty())
                            || StringUtils.hasText(delta.finishReason())
                            || (delta.usage() != null && !delta.usage().isEmpty())))
                    .doOnNext(delta -> callLogger.appendDeltaLog(responseBuffer, delta, traceId, spec.stage()))
                    .doOnComplete(() -> callLogger.info(
                            log,
                            callLogger.message(traceId, spec.stage(), "LLM delta stream response finished in {} ms:\n{}"),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer
                    ))
                    .doOnError(ex -> log.error(
                            callLogger.message(traceId, spec.stage(), "LLM delta stream failed in {} ms, partial response:\n{}"),
                            callLogger.elapsedMs(startNanos),
                            responseBuffer,
                            ex
                    ))
                    .doOnCancel(() -> callLogger.info(
                            log,
                            callLogger.message(traceId, spec.stage(), "LLM delta stream canceled in {} ms, partial response:\n{}"),
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

    private String safeJson(Object value) {
        try {
            return callLogger.sanitizeText(new ObjectMapper().writeValueAsString(value));
        } catch (Exception ex) {
            return callLogger.sanitizeText(String.valueOf(value));
        }
    }

}
