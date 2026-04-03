package com.linlay.agentplatform.stream.model;

import org.springframework.util.StringUtils;

public record RunScope(
        String chatId,
        String runId
) {

    public RunScope {
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (!StringUtils.hasText(runId)) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }

    public static RunScope primary(String chatId, String runId) {
        return new RunScope(chatId, runId);
    }
}
