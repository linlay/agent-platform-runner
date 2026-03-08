package com.linlay.agentplatform.service;

import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.stream.model.LlmDelta;
import reactor.core.publisher.Flux;

import java.util.List;

class AnthropicSseClient {

    Flux<LlmDelta> streamDeltasRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            List<LlmService.LlmFunctionTool> tools,
            boolean parallelToolCalls,
            ToolChoice toolChoice,
            String jsonSchema,
            ComputePolicy computePolicy,
            boolean reasoningEnabled,
            Integer maxTokens,
            String traceId,
            String stage
    ) {
        return Flux.error(notImplemented(providerKey));
    }

    Flux<String> streamContentRawSse(
            String providerKey,
            String model,
            String systemPrompt,
            List<ChatMessage> historyMessages,
            String userPrompt,
            String stage
    ) {
        return Flux.error(notImplemented(providerKey));
    }

    RuntimeException notImplemented(String providerKey) {
        return new UnsupportedOperationException(
                "Anthropic protocol is not implemented yet for provider: " + providerKey
        );
    }
}
