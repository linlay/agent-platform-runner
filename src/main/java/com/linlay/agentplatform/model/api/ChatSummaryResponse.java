package com.linlay.agentplatform.model.api;

public record ChatSummaryResponse(
        String chatId,
        String chatName,
        String firstAgentKey,
        long createdAt,
        long updatedAt
) {
}
