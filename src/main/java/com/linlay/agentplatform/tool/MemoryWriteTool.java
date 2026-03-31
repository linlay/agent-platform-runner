package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.memory.AgentMemoryStore;
import com.linlay.agentplatform.service.memory.MemoryRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnBean(AgentMemoryStore.class)
public class MemoryWriteTool extends AbstractDeterministicTool implements ContextAwareTool {

    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties properties;
    public MemoryWriteTool(
            AgentMemoryStore agentMemoryStore,
            AgentMemoryProperties properties
    ) {
        this.agentMemoryStore = agentMemoryStore;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
    }

    @Override
    public String name() {
        return "_memory_write_";
    }

    @Override
    public String description() {
        return "写入 agent 持久化记忆，支持分类、重要度和标签。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        throw new IllegalArgumentException("_memory_write_ requires execution context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        AgentDefinition definition = requireDefinition(context);
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String content = requireText(root, "content");
        String category = readText(root, "category");
        Integer importance = readInteger(root, "importance");
        if (importance == null) {
            importance = 5;
        }
        List<String> tags = readStringList(root.get("tags"));

        MemoryRecord record = agentMemoryStore.write(new AgentMemoryStore.WriteRequest(
                definition.id(),
                context.request() == null ? null : context.request().requestId(),
                context.request() == null ? null : context.request().chatId(),
                null,
                content,
                "tool-write",
                category,
                importance,
                tags
        ));

        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("id", record.id());
        result.put("status", "stored");
        result.put("subjectKey", record.subjectKey());
        result.put("sourceType", record.sourceType());
        result.put("category", record.category());
        result.put("importance", record.importance());
        result.put("hasEmbedding", record.hasEmbedding());
        return result;
    }

    private AgentDefinition requireDefinition(ExecutionContext context) {
        if (context == null || context.definition() == null) {
            throw new IllegalArgumentException("_memory_write_ requires an active agent execution context");
        }
        return context.definition();
    }
    private String requireText(JsonNode root, String fieldName) {
        String value = readText(root, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing argument: " + fieldName);
        }
        return value;
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer readInteger(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.intValue();
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an integer");
            }
        }
        throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an integer");
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid argument: tags must be an array of strings");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull() || !item.isValueNode()) {
                throw new IllegalArgumentException("Invalid argument: tags must be an array of strings");
            }
            String value = item.asText();
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }
}
