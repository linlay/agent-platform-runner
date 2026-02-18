package com.linlay.springaiagw.model.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgwSubmitRequest(
        @NotBlank
        String runId,
        @NotBlank
        String toolId,
        @NotNull
        Object params
) {
}
