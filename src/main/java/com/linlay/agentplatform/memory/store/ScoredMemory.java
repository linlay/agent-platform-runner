package com.linlay.agentplatform.memory.store;

public record ScoredMemory(
        MemoryRecord memory,
        double score,
        String matchType
) {
}
