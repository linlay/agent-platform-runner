package com.linlay.springaiagw.agent;

import com.linlay.springaiagw.model.ProviderType;

import java.util.List;
import java.util.Locale;

public record AgentDefinition(
        String id,
        String description,
        String providerKey,
        String model,
        String systemPrompt,
        AgentMode mode,
        List<String> tools
) {

    @Deprecated
    public AgentDefinition(
            String id,
            String description,
            ProviderType providerType,
            String model,
            String systemPrompt,
            AgentMode mode,
            List<String> tools
    ) {
        this(
                id,
                description,
                providerType == null ? "bailian" : providerType.name().toLowerCase(Locale.ROOT),
                model,
                systemPrompt,
                mode,
                tools
        );
    }
}
