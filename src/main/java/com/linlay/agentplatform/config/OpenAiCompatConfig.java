package com.linlay.agentplatform.config;

public record OpenAiCompatConfig(
        OpenAiCompatRequestConfig request,
        OpenAiCompatResponseConfig response
) {
}
