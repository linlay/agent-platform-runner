package com.linlay.agentplatform.model.api;

public record InterruptResponse(
        boolean accepted,
        String status,
        String runId,
        String detail
) {
}
