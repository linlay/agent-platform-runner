package com.linlay.agentplatform.config;

import java.util.List;

public record OpenAiCompatResponseConfig(
        List<ReasoningFormat> reasoningFormats,
        ThinkTagConfig thinkTag
) {

    public OpenAiCompatResponseConfig {
        reasoningFormats = reasoningFormats == null ? null : List.copyOf(reasoningFormats);
    }
}
