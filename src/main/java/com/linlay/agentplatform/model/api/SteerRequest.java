package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record SteerRequest(
        String requestId,
        String chatId,
        @NotBlank
        String runId,
        String steerId,
        String agentKey,
        String teamId,
        @NotBlank
        String message,
        Boolean planningMode
) {
}
