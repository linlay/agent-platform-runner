package com.linlay.agentplatform.model.api;

import java.util.List;

public record AgentListResponse(
        List<AgentSummary> agents
) {
    public record AgentSummary(
            String key,
            String name,
            Object icon,
            String description,
            String role
    ) {
    }
}
