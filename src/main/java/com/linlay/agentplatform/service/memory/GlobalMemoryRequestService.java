package com.linlay.agentplatform.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GlobalMemoryRequestService {

    private static final String PROMPT_KEY_REMEMBER = "remember";

    private final ChatRecordStore chatRecordStore;
    private final ObjectMapper objectMapper;
    private final RootProperties rootProperties;
    private final Map<String, String> prompts;

    public GlobalMemoryRequestService(
            ChatRecordStore chatRecordStore,
            ObjectMapper objectMapper,
            RootProperties rootProperties
    ) {
        this.chatRecordStore = chatRecordStore;
        this.objectMapper = objectMapper;
        this.rootProperties = rootProperties == null ? new RootProperties() : rootProperties;
        this.prompts = Map.of(
                "remember", loadPrompt("prompts/remember.txt"),
                "learn", loadPrompt("prompts/learn.txt")
        );
    }

    public CaptureResult captureRemember(RememberRequest request) {
        String requestId = normalizeRequestId(request == null ? null : request.requestId());
        String chatId = requireText(request == null ? null : request.chatId(), "chatId");
        ChatDetailResponse detail = chatRecordStore.loadChat(chatId, true);
        long createdAt = System.currentTimeMillis();
        String relativePath = "remember/" + chatId + "/" + requestId + ".json";
        Path targetPath = resolveMemoryRoot().resolve(relativePath);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "remember-request");
        payload.put("requestId", requestId);
        payload.put("chatId", chatId);
        payload.put("createdAt", createdAt);
        payload.put("prompt", prompt(PROMPT_KEY_REMEMBER));
        payload.put("promptKey", PROMPT_KEY_REMEMBER);

        Map<String, Object> chatPayload = new LinkedHashMap<>();
        chatPayload.put("chatId", detail.chatId());
        chatPayload.put("chatName", detail.chatName());
        chatPayload.put("rawMessages", detail.rawMessages());
        chatPayload.put("events", detail.events());
        chatPayload.put("references", detail.references());
        payload.put("chat", chatPayload);

        writeJson(targetPath, payload);
        return new CaptureResult(requestId, chatId, relativePath, "remember request captured");
    }

    public Path resolveMemoryRoot() {
        return Path.of(rootProperties.getExternalDir()).toAbsolutePath().normalize().resolve("memory");
    }

    String prompt(String promptKey) {
        String value = prompts.get(promptKey);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Prompt not found for key=" + promptKey);
        }
        return value;
    }

    private void writeJson(Path targetPath, Map<String, Object> payload) {
        try {
            Files.createDirectories(targetPath.getParent());
            Files.writeString(
                    targetPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write global memory request: " + targetPath, ex);
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

    public record CaptureResult(
            String requestId,
            String chatId,
            String memoryPath,
            String detail
    ) {
    }
}
