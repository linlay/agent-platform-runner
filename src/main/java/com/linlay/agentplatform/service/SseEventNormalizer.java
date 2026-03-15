package com.linlay.agentplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.FrontendToolProperties;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Extracted SSE event normalization from AgentQueryService.
 */
final class SseEventNormalizer {

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ViewportRegistryService viewportRegistryService;
    private final FrontendToolProperties frontendToolProperties;

    SseEventNormalizer(
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            ViewportRegistryService viewportRegistryService,
            FrontendToolProperties frontendToolProperties
    ) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.viewportRegistryService = viewportRegistryService;
        this.frontendToolProperties = frontendToolProperties;
    }

    ServerSentEvent<String> normalizeEvent(ServerSentEvent<String> event, Set<String> hiddenToolIds) {
        if (event == null) {
            return null;
        }

        ServerSentEvent<String> heartbeatNormalized = normalizeHeartbeatCommentEvent(event);
        if (heartbeatNormalized != event) {
            return heartbeatNormalized;
        }

        if (!StringUtils.hasText(event.data())) {
            return event;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(event.data());
        } catch (Exception ignored) {
            return event;
        }
        if (!(root instanceof ObjectNode objectNode)) {
            return event;
        }

        String type = objectNode.path("type").asText();
        if ("plan.update".equals(type)) {
            ObjectNode normalized = objectMapper.createObjectNode();
            putIfPresent(normalized, "seq", objectNode.get("seq"));
            normalized.put("type", "plan.update");
            putIfPresent(normalized, "planId", objectNode.get("planId"));
            putIfPresent(normalized, "chatId", objectNode.get("chatId"));
            putIfPresent(normalized, "plan", objectNode.get("plan"));
            putIfPresent(normalized, "timestamp", objectNode.get("timestamp"));
            return rebuildEvent(event, normalized);
        }

        if (shouldHideToolEvent(type, objectNode, hiddenToolIds)) {
            return null;
        }

        if (normalizeFrontendToolEvent(type, objectNode)) {
            return rebuildEvent(event, objectNode);
        }

        return event;
    }

    ServerSentEvent<String> normalizeHeartbeatCommentEvent(ServerSentEvent<String> event) {
        if (!StringUtils.hasText(event.comment())
                || StringUtils.hasText(event.event())
                || StringUtils.hasText(event.data())) {
            return event;
        }
        if (!"heartbeat".equals(event.comment().trim())) {
            return event;
        }

        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder();
        builder.event("heartbeat");
        if (StringUtils.hasText(event.id())) {
            builder.id(event.id());
        }
        if (event.retry() != null) {
            builder.retry(event.retry());
        }
        return builder.build();
    }

    boolean normalizeFrontendToolEvent(String eventType, ObjectNode root) {
        if (!"tool.start".equals(eventType) && !"tool.snapshot".equals(eventType)) {
            return false;
        }

        String toolName = root.path("toolName").asText(null);
        if (!StringUtils.hasText(toolName)) {
            return false;
        }

        return toolRegistry.descriptor(toolName)
                .filter(ToolDescriptor::hasViewport)
                .map(descriptor -> {
                    String viewportKey = StringUtils.hasText(descriptor.viewportKey())
                            ? descriptor.viewportKey().trim()
                            : null;
                    if (!StringUtils.hasText(viewportKey)) {
                        return false;
                    }
                    root.put("viewportKey", viewportKey);
                    root.put("toolType", resolveViewportToolType(descriptor.toolType(), viewportKey));
                    root.put("toolTimeout", Math.max(1L, frontendToolProperties.getSubmitTimeoutMs()));
                    return true;
                })
                .orElse(false);
    }

    boolean shouldHideToolEvent(String eventType, ObjectNode root, Set<String> hiddenToolIds) {
        if (root == null || hiddenToolIds == null || !StringUtils.hasText(eventType)) {
            return false;
        }
        if ("tool.start".equals(eventType) || "tool.snapshot".equals(eventType)) {
            String toolName = root.path("toolName").asText(null);
            String toolId = root.path("toolId").asText(null);
            boolean hidden = toolRegistry.descriptor(toolName)
                    .map(descriptor -> Boolean.FALSE.equals(descriptor.clientVisible()))
                    .orElse(false);
            if (hidden && StringUtils.hasText(toolId)) {
                hiddenToolIds.add(toolId.trim());
            }
            return hidden;
        }
        if (!eventType.startsWith("tool.")) {
            return false;
        }
        String toolId = root.path("toolId").asText(null);
        if (!StringUtils.hasText(toolId)) {
            return false;
        }
        boolean hidden = hiddenToolIds.contains(toolId.trim());
        if (hidden && "tool.result".equals(eventType)) {
            hiddenToolIds.remove(toolId.trim());
        }
        return hidden;
    }

    String resolveViewportToolType(String descriptorToolType, String viewportKey) {
        if (StringUtils.hasText(descriptorToolType)) {
            return descriptorToolType.trim();
        }
        return viewportRegistryService.find(viewportKey)
                .map(viewport -> viewport.viewportType().value())
                .filter(StringUtils::hasText)
                .orElse("html");
    }

    private void putIfPresent(ObjectNode target, String key, JsonNode value) {
        if (target == null || key == null || value == null || value.isMissingNode()) {
            return;
        }
        target.set(key, value);
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private ServerSentEvent<String> rebuildEvent(ServerSentEvent<String> original, ObjectNode data) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(toJson(data));
        if (StringUtils.hasText(original.event())) {
            builder.event(original.event());
        }
        if (StringUtils.hasText(original.id())) {
            builder.id(original.id());
        }
        if (StringUtils.hasText(original.comment())) {
            builder.comment(original.comment());
        }
        if (original.retry() != null) {
            builder.retry(original.retry());
        }
        return builder.build();
    }
}
