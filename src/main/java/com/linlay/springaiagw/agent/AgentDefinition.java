package com.linlay.springaiagw.agent;

import com.linlay.springaiagw.model.ProviderType;

public record AgentDefinition(
        String id,
        String description,
        ProviderType providerType,
        String model,
        String systemPrompt,
        boolean deepThink,
        AgentMode mode
) {
}
