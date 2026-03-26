package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.memory.AgentMemoryStore;
import com.linlay.agentplatform.service.memory.MemoryRecord;
import com.linlay.agentplatform.service.memory.ScoredMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnBean(AgentMemoryStore.class)
public class MemorySearchTool extends AbstractDeterministicTool implements ContextAwareTool {

    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties properties;

    public MemorySearchTool(AgentMemoryStore agentMemoryStore, AgentMemoryProperties properties) {
        this.agentMemoryStore = agentMemoryStore;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
    }

    @Override
    public String name() {
        return "_memory_search_";
    }

    @Override
    public String description() {
        return "检索 agent 持久化记忆，支持 FTS 与可选向量混合搜索。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        throw new IllegalArgumentException("_memory_search_ requires execution context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        AgentDefinition definition = requireDefinition(context);
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String query = readRequiredText(root, "query");
        String category = readText(root, "category");
        Integer limit = readInteger(root, "limit");

        List<ScoredMemory> results = agentMemoryStore.search(
                definition.id(),
                definition.agentDir(),
                query,
                category,
                limit == null ? properties.getSearchDefaultLimit() : limit
        );
        ArrayNode items = OBJECT_MAPPER.createArrayNode();
        for (ScoredMemory result : results) {
            ObjectNode item = OBJECT_MAPPER.createObjectNode();
            item.set("memory", toJson(result.memory()));
            item.put("score", result.score());
            item.put("matchType", result.matchType());
            items.add(item);
        }
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("count", results.size());
        payload.set("results", items);
        return payload;
    }

    private AgentDefinition requireDefinition(ExecutionContext context) {
        if (context == null || context.definition() == null) {
            throw new IllegalArgumentException("_memory_search_ requires an active agent execution context");
        }
        return context.definition();
    }

    private ObjectNode toJson(MemoryRecord record) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("id", record.id());
        node.put("agentKey", record.agentKey());
        node.put("content", record.content());
        node.put("category", record.category());
        node.put("importance", record.importance());
        node.set("tags", OBJECT_MAPPER.valueToTree(record.tags()));
        node.put("hasEmbedding", record.hasEmbedding());
        node.put("createdAt", record.createdAt());
        node.put("updatedAt", record.updatedAt());
        node.put("accessCount", record.accessCount());
        if (record.lastAccessedAt() == null) {
            node.putNull("lastAccessedAt");
        } else {
            node.put("lastAccessedAt", record.lastAccessedAt());
        }
        return node;
    }

    private String readRequiredText(JsonNode root, String fieldName) {
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
}
