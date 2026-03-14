package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record InterruptRequest(
        String requestId,
        String chatId,
        @NotBlank
        String runId,
        String agentKey,
        String teamId,
        String message,
        Boolean planningMode
) {
}
