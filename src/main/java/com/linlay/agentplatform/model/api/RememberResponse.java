package com.linlay.agentplatform.model.api;

public record RememberResponse(
        boolean accepted,
        String status,
        String requestId,
        String chatId,
        String memoryPath,
        int memoryCount,
        String detail
) {
}
