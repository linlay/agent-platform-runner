package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.service.memory.MemoryRecord;

public final class MemoryToolSupport {

    private MemoryToolSupport() {
    }

    public static AgentDefinition requireDefinition(ExecutionContext context, String toolName) {
        if (context == null || context.definition() == null) {
            throw new IllegalArgumentException(toolName + " requires an active agent execution context");
        }
        return context.definition();
    }

    public static ObjectNode toJson(MemoryRecord record) {
        ObjectNode node = AbstractDeterministicTool.OBJECT_MAPPER.createObjectNode();
        node.put("id", record.id());
        node.put("agentKey", record.agentKey());
        node.put("subjectKey", record.subjectKey());
        node.put("content", record.content());
        node.put("sourceType", record.sourceType());
        node.put("category", record.category());
        node.put("importance", record.importance());
        node.set("tags", AbstractDeterministicTool.OBJECT_MAPPER.valueToTree(record.tags()));
        node.put("hasEmbedding", record.hasEmbedding());
        if (record.embeddingModel() == null) {
            node.putNull("embeddingModel");
        } else {
            node.put("embeddingModel", record.embeddingModel());
        }
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
}
