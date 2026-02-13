package com.linlay.springaiagw.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record AgentRequest(
        @NotBlank
        String message,
        String chatId,
        String requestId,
        String runId,
        Map<String, Object> query
) {

    public AgentRequest(
            String message,
            String chatId,
            String requestId,
            String runId
    ) {
        this(message, chatId, requestId, runId, null);
    }
}
