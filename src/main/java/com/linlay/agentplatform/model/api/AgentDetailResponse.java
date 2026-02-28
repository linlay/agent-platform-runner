package com.linlay.agentplatform.model.api;

import java.util.Map;

public record AgentDetailResponse(
        AgentDetail agent
) {
    public record AgentDetail(
            String key,
            String name,
            Object icon,
            String description,
            String instructions,
            Map<String, Object> meta
    ) {
    }
}
