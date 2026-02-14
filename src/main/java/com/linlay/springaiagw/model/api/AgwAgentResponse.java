package com.linlay.springaiagw.model.api;

import java.util.List;
import java.util.Map;

public record AgwAgentResponse(
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
