package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record LearnRequest(
        @NotBlank
        String requestId,
        @NotBlank
        String chatId,
        @NotBlank
        String runId,
        @NotBlank
        String agentKey,
        String subjectKey
) {
}
