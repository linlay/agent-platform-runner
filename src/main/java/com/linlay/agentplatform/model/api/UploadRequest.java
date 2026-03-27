package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UploadRequest(
        @NotBlank
        String requestId,
        @NotBlank
        String chatId,
        @NotBlank
        String type,
        @NotBlank
        String name,
        @NotNull
        @PositiveOrZero
        Long sizeBytes,
        @NotBlank
        String mimeType,
        String sha256
) {
}
