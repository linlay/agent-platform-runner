package com.linlay.agentplatform.service.memory;

import java.util.List;

public record MemoryRecord(
        String id,
        String agentKey,
        String subjectKey,
        String content,
        String sourceType,
        String category,
        int importance,
        List<String> tags,
        boolean hasEmbedding,
        String embeddingModel,
        long createdAt,
        long updatedAt,
        int accessCount,
        Long lastAccessedAt
) {
}
