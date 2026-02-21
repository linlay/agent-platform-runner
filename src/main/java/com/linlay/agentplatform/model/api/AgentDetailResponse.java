package com.linlay.agentplatform.model.api;

import java.util.List;
import java.util.Map;

public record AgentDetailResponse(
        AgentDetail agent
) {
    public record AgentDetail(
            String key,
            String name,
            String description,
            String instructions,
            List<String> capabilities,
            Map<String, Object> meta
    ) {
    }
}
