package com.linlay.agentplatform.model.api;

public record LearnResponse(
        boolean accepted,
        String status,
        String requestId,
        String chatId,
        String runId,
        String subjectKey,
        String detail
) {
}
