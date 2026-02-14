package com.linlay.springaiagw.model.api;

public record AgwChatSummaryResponse(
        String chatId,
        String chatName,
        String firstAgentKey,
        long createdAt,
        long updatedAt
) {
}
