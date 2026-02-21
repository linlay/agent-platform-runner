package com.linlay.agentplatform.model.api;

public record SubmitResponse(
        boolean accepted,
        String status,
        String runId,
        String toolId,
        String detail
) {
}
