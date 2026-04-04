package com.linlay.agentplatform.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.model.api.RememberResponse;
import com.linlay.agentplatform.model.ModelDefinition;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import com.linlay.agentplatform.service.llm.LlmService;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Service
public class GlobalMemoryRequestService {

    private static final Logger log = LoggerFactory.getLogger(GlobalMemoryRequestService.class);
    private static final String PROMPT_KEY_REMEMBER = "remember";

    private final ChatRecordStore chatRecordStore;
    private final ObjectMapper objectMapper;
    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryService agentMemoryService;
    private final LlmService llmService;
    private final ModelRegistryService modelRegistryService;
    private final AgentMemoryProperties memoryProperties;
    private final Map<String, String> prompts;

    public GlobalMemoryRequestService(
            ChatRecordStore chatRecordStore,
            ObjectMapper objectMapper,
            ObjectProvider<AgentMemoryStore> agentMemoryStoreProvider,
            AgentMemoryService agentMemoryService,
            LlmService llmService,
            ModelRegistryService modelRegistryService,
            AgentMemoryProperties memoryProperties
    ) {
        this.chatRecordStore = chatRecordStore;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.agentMemoryService = agentMemoryService == null ? new com.linlay.agentplatform.service.memory.AgentMemoryService() : agentMemoryService;
        this.agentMemoryStore = agentMemoryStoreProvider == null
                ? new AgentMemoryStore(new com.linlay.agentplatform.config.properties.AgentMemoryProperties(), this.agentMemoryService, null)
                : Optional.ofNullable(agentMemoryStoreProvider.getIfAvailable())
                .orElseGet(() -> new AgentMemoryStore(
                        new com.linlay.agentplatform.config.properties.AgentMemoryProperties(),
                        this.agentMemoryService,
                        null
                ));
        this.llmService = llmService;
        this.modelRegistryService = modelRegistryService;
        this.memoryProperties = memoryProperties == null ? new AgentMemoryProperties() : memoryProperties;
        this.prompts = Map.of(
                "remember", loadPrompt("prompts/remember.txt"),
                "learn", loadPrompt("prompts/learn.txt")
        );
    }

    public Mono<CaptureResult> captureRemember(RememberRequest request) {
        return Mono.defer(() -> {
            String requestId = normalizeRequestId(request == null ? null : request.requestId());
            String chatId = requireText(request == null ? null : request.chatId(), "chatId");
            ChatDetailResponse detail = chatRecordStore.loadChat(chatId, true);
            String agentKey = chatRecordStore.findBoundAgentKey(chatId).filter(StringUtils::hasText).map(String::trim).orElse("_unknown");
            RememberResponse.PromptPreviewResponse promptPreview = buildPromptPreview(detail);
            RememberModelSelection selection = resolveRememberModelSelection();
            String userPrompt = buildRememberUserPrompt(detail);

            log.info(
                    "remember extraction start requestId={}, chatId={}, modelKey={}, providerKey={}, modelId={}, protocol={}, timeoutMs={}",
                    requestId,
                    chatId,
                    selection.modelKey(),
                    selection.providerKey(),
                    selection.modelId(),
                    selection.protocol(),
                    selection.timeoutMs()
            );

            return extractRememberCandidates(selection, userPrompt)
                    .map(candidates -> buildCaptureResult(requestId, chatId, agentKey, promptPreview, candidates))
                    .onErrorMap(ex -> ex instanceof RememberCaptureException
                            ? ex
                            : new RememberCaptureException("remember extraction failed: " + safeReason(ex), ex))
                    .doOnSuccess(result -> log.info(
                            "remember extraction finished requestId={}, chatId={}, modelKey={}, memoryCount={}",
                            requestId,
                            chatId,
                            selection.modelKey(),
                            result.memoryCount()
                    ))
                    .doOnError(ex -> log.warn(
                            "remember extraction failed requestId={}, chatId={}, modelKey={}, providerKey={}, modelId={}, timeoutMs={}, reason={}",
                            requestId,
                            chatId,
                            selection.modelKey(),
                            selection.providerKey(),
                            selection.modelId(),
                            selection.timeoutMs(),
                            ex.getMessage(),
                            ex
                    ));
        });
    }

    String prompt(String promptKey) {
        String value = prompts.get(promptKey);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Prompt not found for key=" + promptKey);
        }
        return value;
    }

    private Mono<List<RememberCandidate>> extractRememberCandidates(RememberModelSelection selection, String userPrompt) {
        if (llmService == null) {
            return Mono.error(new RememberCaptureException("remember extraction failed: llm service is not configured"));
        }
        return llmService.completeText(
                        selection.modelKey(),
                        selection.providerKey(),
                        selection.modelId(),
                        selection.protocol(),
                        prompt(PROMPT_KEY_REMEMBER),
                        userPrompt,
                        PROMPT_KEY_REMEMBER
                )
                .timeout(Duration.ofMillis(selection.timeoutMs()))
                .onErrorMap(TimeoutException.class,
                        ex -> new RememberCaptureException("remember extraction timed out after " + selection.timeoutMs() + " ms", ex))
                .onErrorMap(ex -> ex instanceof RememberCaptureException
                        ? ex
                        : new RememberCaptureException("remember extraction failed: " + safeReason(ex), ex))
                .map(this::parseRememberCandidates);
    }

    private String buildRememberUserPrompt(ChatDetailResponse detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chatId", detail.chatId());
        payload.put("chatName", detail.chatName());
        payload.put("rawMessages", detail.rawMessages());
        payload.put("events", detail.events());
        payload.put("references", detail.references());
        return """
                请从以下对话快照中抽取可长期保留的记忆，返回 JSON。
                返回格式必须是：
                {"items":[{"summary":"一句话总结","subjectKey":"可选，不确定可省略"}]}
                只返回 JSON，不要输出 Markdown。

                chat:
                %s
                """.formatted(toJson(payload));
    }

    private RememberResponse.PromptPreviewResponse buildPromptPreview(ChatDetailResponse detail) {
        List<Map<String, Object>> rawMessages = detail.rawMessages() == null ? List.of() : detail.rawMessages();
        List<Map<String, Object>> events = detail.events() == null ? List.of() : detail.events();
        List<?> references = detail.references() == null ? List.of() : detail.references();
        List<String> rawMessageSamples = sampleObjects(rawMessages);
        List<String> eventSamples = sampleObjects(events);
        List<String> referenceSamples = references.stream()
                .limit(3)
                .map(this::toCompactJson)
                .map(this::truncatePreview)
                .toList();
        Map<String, Object> previewPayload = new LinkedHashMap<>();
        previewPayload.put("chatId", detail.chatId());
        previewPayload.put("chatName", detail.chatName());
        previewPayload.put("rawMessageCount", rawMessages.size());
        previewPayload.put("eventCount", events.size());
        previewPayload.put("referenceCount", references.size());
        previewPayload.put("rawMessageSamples", rawMessageSamples);
        previewPayload.put("eventSamples", eventSamples);
        previewPayload.put("referenceSamples", referenceSamples);
        String previewJson = toJson(previewPayload);
        return new RememberResponse.PromptPreviewResponse(
                prompt(PROMPT_KEY_REMEMBER),
                """
                请从以下对话快照中抽取可长期保留的记忆，返回 JSON。
                返回格式必须是：
                {"items":[{"summary":"一句话总结","subjectKey":"可选，不确定可省略"}]}
                只返回 JSON，不要输出 Markdown。

                chat preview:
                %s
                """.formatted(previewJson),
                detail.chatName(),
                rawMessages.size(),
                events.size(),
                references.size(),
                rawMessageSamples,
                eventSamples,
                referenceSamples
        );
    }

    private List<RememberCandidate> parseRememberCandidates(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new RememberCaptureException("remember extraction failed: empty response");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode itemsNode = root.isArray() ? root : root.path("items");
            if (!itemsNode.isArray()) {
                throw new RememberCaptureException("remember extraction failed: invalid response payload");
            }
            List<RememberCandidate> items = new ArrayList<>();
            for (JsonNode item : itemsNode) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String summary = normalizeSummary(item.path("summary").asText(null));
                if (!StringUtils.hasText(summary)) {
                    continue;
                }
                items.add(new RememberCandidate(
                        summary,
                        normalizeNullable(item.path("subjectKey").asText(null))
                ));
            }
            return List.copyOf(items);
        } catch (RememberCaptureException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RememberCaptureException("remember extraction failed: invalid JSON response", ex);
        }
    }

    private CaptureResult buildCaptureResult(
            String requestId,
            String chatId,
            String agentKey,
            RememberResponse.PromptPreviewResponse promptPreview,
            List<RememberCandidate> candidates
    ) {
        int stored = 0;
        List<RememberResponse.StoredMemoryResponse> storedItems = new ArrayList<>();
        for (RememberCandidate candidate : candidates) {
            if (!StringUtils.hasText(candidate.summary())) {
                continue;
            }
            MemoryRecord record = agentMemoryStore.write(new AgentMemoryStore.WriteRequest(
                    agentKey,
                    requestId,
                    chatId,
                    candidate.subjectKey(),
                    candidate.summary(),
                    "remember",
                    "remember",
                    6,
                    List.of("remember")
            ));
            storedItems.add(toStoredMemoryResponse(record, requestId, chatId));
            stored++;
        }

        String relativePath = agentMemoryService.relativeJournalPath(LocalDate.now());
        String memoryRoot = agentMemoryService.resolveMemoryRoot().toString();
        String detailMessage = stored == 0
                ? "remember request captured with no extracted memory; memory root=" + memoryRoot
                : "remember request captured; memory root=" + memoryRoot;
        List<RememberResponse.RememberItemResponse> items = candidates.stream()
                .map(candidate -> new RememberResponse.RememberItemResponse(candidate.summary(), candidate.subjectKey()))
                .toList();
        return new CaptureResult(requestId, chatId, relativePath, memoryRoot, stored, detailMessage, promptPreview, items, storedItems);
    }

    private RememberModelSelection resolveRememberModelSelection() {
        String modelKey = normalizeNullable(memoryProperties.getRemember().getModelKey());
        if (!StringUtils.hasText(modelKey)) {
            throw new RememberCaptureException("remember extraction failed: remember modelKey is not configured");
        }
        if (modelRegistryService == null) {
            throw new RememberCaptureException("remember extraction failed: model registry is not configured");
        }
        ModelDefinition model = modelRegistryService.find(modelKey).orElse(null);
        if (model == null) {
            throw new RememberCaptureException("remember extraction failed: remember modelKey not found: " + modelKey);
        }
        long timeoutMs = memoryProperties.getRemember().getTimeoutMs() > 0
                ? memoryProperties.getRemember().getTimeoutMs()
                : 60_000L;
        return new RememberModelSelection(
                model.key(),
                model.provider(),
                model.modelId(),
                model.protocol(),
                timeoutMs
        );
    }

    private String normalizeSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        String normalized = summary.replaceAll("\\s+", " ").trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private List<String> sampleObjects(List<Map<String, Object>> items) {
        return items.stream()
                .limit(3)
                .map(this::toCompactJson)
                .map(this::truncatePreview)
                .toList();
    }

    private String toCompactJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize remember preview payload", ex);
        }
    }

    private String truncatePreview(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 144) + "...[truncated]";
    }

    private RememberResponse.StoredMemoryResponse toStoredMemoryResponse(MemoryRecord record, String requestId, String chatId) {
        return new RememberResponse.StoredMemoryResponse(
                record.id(),
                requestId,
                chatId,
                record.agentKey(),
                record.subjectKey(),
                record.content(),
                record.sourceType(),
                record.category(),
                record.importance(),
                record.tags(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize remember payload", ex);
        }
    }

    private String loadPrompt(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load prompt resource: " + classpathLocation, ex);
        }
    }

    private String normalizeRequestId(String requestId) {
        String normalized = requireText(requestId, "requestId");
        if (normalized.contains("/") || normalized.contains("\\") || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("requestId must be a safe path segment");
        }
        return normalized;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeReason(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        return StringUtils.hasText(message) ? message.trim() : ex == null ? "unknown error" : ex.getClass().getSimpleName();
    }

    private record RememberCandidate(String summary, String subjectKey) {
    }

    private record RememberModelSelection(
            String modelKey,
            String providerKey,
            String modelId,
            com.linlay.agentplatform.model.ModelProtocol protocol,
            long timeoutMs
    ) {
    }

    public record CaptureResult(
            String requestId,
            String chatId,
            String memoryPath,
            String memoryRoot,
            int memoryCount,
            String detail,
            RememberResponse.PromptPreviewResponse promptPreview,
            List<RememberResponse.RememberItemResponse> items,
            List<RememberResponse.StoredMemoryResponse> stored
    ) {
    }
}
