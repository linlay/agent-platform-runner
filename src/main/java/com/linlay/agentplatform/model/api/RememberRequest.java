package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;

public record RememberRequest(
        @NotBlank
        String requestId,
        @NotBlank
        String chatId
) {
}
