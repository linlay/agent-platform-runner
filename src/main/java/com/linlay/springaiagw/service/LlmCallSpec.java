package com.linlay.springaiagw.service;

import com.linlay.springaiagw.agent.runtime.policy.ComputePolicy;
import com.linlay.springaiagw.agent.runtime.policy.ToolChoice;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record LlmCallSpec(
        String providerKey,
        String model,
        String systemPrompt,
        List<Message> messages,
        String userPrompt,
        List<LlmService.LlmFunctionTool> tools,
        ToolChoice toolChoice,
        String jsonSchema,
        ComputePolicy compute,
        boolean reasoningEnabled,
        Integer maxTokens,
        Long timeoutMs,
        String stage,
        boolean parallelToolCalls
) {
    public LlmCallSpec {
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
        if (stage == null || stage.isBlank()) {
            stage = "default";
        }
        if (timeoutMs != null && timeoutMs <= 0) {
            timeoutMs = null;
        }
    }
}
