package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record LearnRequest(
        @NotBlank
        String requestId,
        @NotBlank
        String chatId,
        String subjectKey
) {
}
