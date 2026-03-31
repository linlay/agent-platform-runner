package com.linlay.agentplatform.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ProviderConfig;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import com.linlay.agentplatform.service.llm.LlmService;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class GlobalMemoryRequestService {

    private static final Logger log = LoggerFactory.getLogger(GlobalMemoryRequestService.class);
    private static final String PROMPT_KEY_REMEMBER = "remember";

    private final ChatRecordStore chatRecordStore;
    private final ObjectMapper objectMapper;
    private final AgentMemoryStore agentMemoryStore;
    private final com.linlay.agentplatform.agent.AgentMemoryService agentMemoryService;
    private final LlmService llmService;
    private final ProviderRegistryService providerRegistryService;
    private final Map<String, String> prompts;

    public GlobalMemoryRequestService(
            ChatRecordStore chatRecordStore,
            ObjectMapper objectMapper,
            ObjectProvider<AgentMemoryStore> agentMemoryStoreProvider,
            com.linlay.agentplatform.agent.AgentMemoryService agentMemoryService,
            LlmService llmService,
            ProviderRegistryService providerRegistryService
    ) {
        this.chatRecordStore = chatRecordStore;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.agentMemoryService = agentMemoryService == null ? new com.linlay.agentplatform.agent.AgentMemoryService() : agentMemoryService;
        this.agentMemoryStore = agentMemoryStoreProvider == null
                ? new AgentMemoryStore(new com.linlay.agentplatform.config.properties.AgentMemoryProperties(), this.agentMemoryService, null)
                : Optional.ofNullable(agentMemoryStoreProvider.getIfAvailable())
                .orElseGet(() -> new AgentMemoryStore(
                        new com.linlay.agentplatform.config.properties.AgentMemoryProperties(),
                        this.agentMemoryService,
                        null
                ));
        this.llmService = llmService;
        this.providerRegistryService = providerRegistryService;
        this.prompts = Map.of(
                "remember", loadPrompt("prompts/remember.txt"),
                "learn", loadPrompt("prompts/learn.txt")
        );
    }

    public CaptureResult captureRemember(RememberRequest request) {
        String requestId = normalizeRequestId(request == null ? null : request.requestId());
        String chatId = requireText(request == null ? null : request.chatId(), "chatId");
        ChatDetailResponse detail = chatRecordStore.loadChat(chatId, true);
        String agentKey = chatRecordStore.findBoundAgentKey(chatId).filter(StringUtils::hasText).map(String::trim).orElse("_unknown");
        List<RememberCandidate> candidates = extractRememberCandidates(detail);

        int stored = 0;
        for (RememberCandidate candidate : candidates) {
            if (!StringUtils.hasText(candidate.summary())) {
                continue;
            }
            agentMemoryStore.write(new AgentMemoryStore.WriteRequest(
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
            stored++;
        }

        String relativePath = agentMemoryService.relativeJournalPath(LocalDate.now());
        String detailMessage = stored == 0 ? "remember request captured with no extracted memory" : "remember request captured";
        return new CaptureResult(requestId, chatId, relativePath, stored, detailMessage);
    }

    String prompt(String promptKey) {
        String value = prompts.get(promptKey);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Prompt not found for key=" + promptKey);
        }
        return value;
    }

    private List<RememberCandidate> extractRememberCandidates(ChatDetailResponse detail) {
        ProviderConfig provider = providerRegistryService == null
                ? null
                : providerRegistryService.list().stream()
                .filter(config -> StringUtils.hasText(config.key()) && StringUtils.hasText(config.defaultModel()))
                .findFirst()
                .orElse(null);
        if (provider == null || llmService == null) {
            return List.of();
        }
        String userPrompt = buildRememberUserPrompt(detail);
        try {
            String raw = llmService.completeText(
                            provider.key(),
                            provider.defaultModel(),
                            prompt(PROMPT_KEY_REMEMBER),
                            userPrompt,
                            PROMPT_KEY_REMEMBER
                    )
                    .toFuture()
                    .get(30, TimeUnit.SECONDS);
            return parseRememberCandidates(raw);
        } catch (Exception ex) {
            log.warn("Failed to extract remember memories, fallback to empty result", ex);
            return List.of();
        }
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

    private List<RememberCandidate> parseRememberCandidates(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode itemsNode = root.isArray() ? root : root.path("items");
            if (!itemsNode.isArray()) {
                return List.of();
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
        } catch (Exception ex) {
            log.warn("Failed to parse remember extraction JSON, fallback to empty result");
            return List.of();
        }
    }

    private String normalizeSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        String normalized = summary.replaceAll("\\s+", " ").trim();
        return StringUtils.hasText(normalized) ? normalized : null;
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

    private record RememberCandidate(String summary, String subjectKey) {
    }

    public record CaptureResult(
            String requestId,
            String chatId,
            String memoryPath,
            int memoryCount,
            String detail
    ) {
    }
}
