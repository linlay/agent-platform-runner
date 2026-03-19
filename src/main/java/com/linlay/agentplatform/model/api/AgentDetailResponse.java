package com.linlay.agentplatform.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.linlay.agentplatform.agent.AgentControl;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDetailResponse(
        String key,
        String name,
        Object icon,
        String description,
        String role,
        String model,
        String mode,
        List<String> tools,
        List<String> skills,
        List<AgentControl> controls,
        Map<String, Object> meta
) {
}
