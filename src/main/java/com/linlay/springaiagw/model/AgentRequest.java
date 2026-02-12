package com.linlay.springaiagw.model;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(
        @NotBlank
        String message,
        String chatId,
        String requestId,
        String runId
) {
}
