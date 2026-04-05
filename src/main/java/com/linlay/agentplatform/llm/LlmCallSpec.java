package com.linlay.agentplatform.llm;

import com.linlay.agentplatform.engine.policy.ComputePolicy;
import com.linlay.agentplatform.engine.policy.ToolChoice;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.model.ModelProtocol;
import reactor.core.publisher.Mono;

import java.util.List;

public record LlmCallSpec(
        String modelKey,
        String providerKey,
        String model,
        ModelProtocol protocol,
        String systemPrompt,
        List<ChatMessage> messages,
        String userPrompt,
        List<LlmService.LlmFunctionTool> tools,
        ToolChoice toolChoice,
        String jsonSchema,
        ComputePolicy compute,
        boolean reasoningEnabled,
        Integer maxTokens,
        Long timeoutMs,
        String stage,
        boolean parallelToolCalls,
        Mono<Void> cancelSignal
) {
    public LlmCallSpec {
        modelKey = modelKey == null || modelKey.isBlank() ? null : modelKey.trim();
        if (messages == null) {
            messages = List.of();
        } else {
            messages = List.copyOf(messages);
        }
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
        if (toolChoice == null) {
            toolChoice = ToolChoice.AUTO;
        }
        if (compute == null) {
            compute = ComputePolicy.MEDIUM;
        }
        if (protocol == null) {
            protocol = ModelProtocol.OPENAI;
        }
        if (stage == null || stage.isBlank()) {
            stage = "default";
        }
        if (timeoutMs != null && timeoutMs <= 0) {
            timeoutMs = null;
        }
        if (cancelSignal == null) {
            cancelSignal = Mono.never();
        }
    }
}
