package com.linlay.agentplatform.model.api;

public record SteerResponse(
        boolean accepted,
        String status,
        String runId,
        String steerId,
        String detail
) {
}
