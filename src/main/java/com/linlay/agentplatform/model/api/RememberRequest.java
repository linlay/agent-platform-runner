package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record RememberRequest(
        @NotBlank
        String requestId,
        @NotBlank
        String chatId,
        @NotBlank
        String runId,
        @NotBlank
        String agentKey
) {
}
