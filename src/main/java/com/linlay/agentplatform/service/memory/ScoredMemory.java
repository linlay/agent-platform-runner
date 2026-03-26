package com.linlay.agentplatform.service.memory;

public record ScoredMemory(
        MemoryRecord memory,
        double score,
        String matchType
) {
}
