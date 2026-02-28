package com.linlay.agentplatform.model.api;

import java.util.List;
import java.util.Map;

public record AgentListResponse(
        List<AgentSummary> agents
) {
    public record AgentSummary(
            String key,
            String name,
            Object icon,
            String description,
            Map<String, Object> meta
    ) {
    }
}
