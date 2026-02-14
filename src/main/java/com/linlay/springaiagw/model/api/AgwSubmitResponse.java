package com.linlay.springaiagw.model.api;

public record AgwSubmitResponse(
        String requestId,
        boolean accepted,
        String runId,
        String toolId
) {
}
