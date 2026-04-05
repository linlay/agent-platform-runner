package com.linlay.agentplatform.memory.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.store.MemoryRecord;
import com.linlay.agentplatform.tool.AbstractDeterministicTool;
import com.linlay.agentplatform.tool.ContextAwareTool;
import com.linlay.agentplatform.tool.ToolJsonHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnBean(AgentMemoryStore.class)
public class MemoryReadTool extends AbstractDeterministicTool implements ContextAwareTool {

    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties properties;

    public MemoryReadTool(AgentMemoryStore agentMemoryStore, AgentMemoryProperties properties) {
        this.agentMemoryStore = agentMemoryStore;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
    }

    @Override
    public String name() {
        return "_memory_read_";
    }

    @Override
    public String description() {
        return "读取 agent 持久化记忆，可按 ID、分类、排序和数量限制查询。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        throw new IllegalArgumentException("_memory_read_ requires execution context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        AgentDefinition definition = MemoryToolSupport.requireDefinition(context, name());
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String id = ToolJsonHelper.readText(root, "id");
        String category = ToolJsonHelper.readText(root, "category");
        Integer limit = ToolJsonHelper.readInteger(root, "limit");
        String sort = ToolJsonHelper.readText(root, "sort");

        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        if (StringUtils.hasText(id)) {
            result.put("found", false);
            agentMemoryStore.read(definition.id(), definition.agentDir(), id).ifPresent(memory -> {
                result.put("found", true);
                result.set("memory", MemoryToolSupport.toJson(memory));
            });
            return result;
        }

        List<MemoryRecord> memories = agentMemoryStore.list(
                definition.id(),
                definition.agentDir(),
                category,
                limit == null ? properties.getSearchDefaultLimit() : limit,
                sort
        );
        ArrayNode items = OBJECT_MAPPER.createArrayNode();
        memories.stream().map(MemoryToolSupport::toJson).forEach(items::add);
        result.put("count", memories.size());
        result.set("results", items);
        return result;
    }
}
