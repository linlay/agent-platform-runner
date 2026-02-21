package com.linlay.agentplatform.model.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitRequest(
        @NotBlank
        String runId,
        @NotBlank
        String toolId,
        @NotNull
        Object params
) {
}
