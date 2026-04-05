package com.linlay.agentplatform.engine.mode;

import com.linlay.agentplatform.engine.policy.ComputePolicy;
import com.linlay.agentplatform.model.ModelProtocol;

import java.util.List;

public record StageSettings(
        String systemPrompt,
        String modelKey,
        String providerKey,
        String model,
        ModelProtocol protocol,
        List<String> tools,
        boolean reasoningEnabled,
        ComputePolicy reasoningEffort,
        boolean deepThinking,
        String instructionsPrompt,
        Integer maxTokens
) {

    public StageSettings(
            String systemPrompt,
            String providerKey,
            String model,
            List<String> tools,
            boolean reasoningEnabled,
            ComputePolicy reasoningEffort
    ) {
        this(systemPrompt, null, providerKey, model, null, tools, reasoningEnabled, reasoningEffort, false, null, null);
    }

    public StageSettings(
            String systemPrompt,
            String providerKey,
            String model,
            List<String> tools,
            boolean reasoningEnabled,
            ComputePolicy reasoningEffort,
            String instructionsPrompt
    ) {
        this(systemPrompt, null, providerKey, model, null, tools, reasoningEnabled, reasoningEffort, false, instructionsPrompt, null);
    }

    public StageSettings {
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
        if (reasoningEffort == null) {
            reasoningEffort = ComputePolicy.MEDIUM;
        }
        if (protocol == null) {
            protocol = ModelProtocol.OPENAI;
        }
        instructionsPrompt = instructionsPrompt == null || instructionsPrompt.isBlank() ? null : instructionsPrompt.trim();
        maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : null;
    }

    public String primaryPrompt() {
        if (instructionsPrompt != null && !instructionsPrompt.isBlank()) {
            return instructionsPrompt;
        }
        return systemPrompt == null ? "" : systemPrompt;
    }
}
