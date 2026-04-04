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
        AgentDefinition definition = MemoryToolSupport.requireDefinition(context, name());
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String content = ToolJsonHelper.requireText(root, "content");
        String category = ToolJsonHelper.readText(root, "category");
        Integer importance = ToolJsonHelper.readInteger(root, "importance");
        if (importance == null) {
            importance = 5;
        }
        List<String> tags = ToolJsonHelper.readStringList(root.get("tags"));

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
}
