package com.linlay.agentplatform.memory.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.store.MemoryRecord;
import com.linlay.agentplatform.memory.store.ScoredMemory;
import com.linlay.agentplatform.tool.AbstractDeterministicTool;
import com.linlay.agentplatform.tool.ContextAwareTool;
import com.linlay.agentplatform.tool.ToolJsonHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

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
        AgentDefinition definition = MemoryToolSupport.requireDefinition(context, name());
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String query = ToolJsonHelper.requireText(root, "query");
        String category = ToolJsonHelper.readText(root, "category");
        Integer limit = ToolJsonHelper.readInteger(root, "limit");

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
            item.set("memory", MemoryToolSupport.toJson(result.memory()));
            item.put("score", result.score());
            item.put("matchType", result.matchType());
            items.add(item);
        }
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("count", results.size());
        payload.set("results", items);
        return payload;
    }
}
