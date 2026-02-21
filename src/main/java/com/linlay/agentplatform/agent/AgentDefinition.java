package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;

import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        String icon,
        String description,
        String providerKey,
        String model,
        AgentRuntimeMode mode,
        RunSpec runSpec,
        AgentMode agentMode,
        List<String> tools,
        List<String> skills
) {
    public AgentDefinition {
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
        if (skills == null) {
            skills = List.of();
        } else {
            skills = List.copyOf(skills);
        }
    }

    public String systemPrompt() {
        return agentMode.primarySystemPrompt();
    }
}
