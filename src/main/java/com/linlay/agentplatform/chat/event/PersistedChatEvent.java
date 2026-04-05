package com.linlay.agentplatform.chat.event;

import java.util.Map;

public record PersistedChatEvent(
        String type,
        long timestamp,
        Map<String, Object> payload,
        int lineIndex
) {
}
