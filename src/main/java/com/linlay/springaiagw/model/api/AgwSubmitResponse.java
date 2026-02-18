package com.linlay.springaiagw.model.api;

public record AgwSubmitResponse(
        boolean accepted,
        String status,
        String runId,
        String toolId,
        String detail
) {
}
