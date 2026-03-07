package com.linlay.agentplatform.service;

import java.util.Map;

record PersistedChatEvent(
        String type,
        long timestamp,
        Map<String, Object> payload,
        int lineIndex
) {
}
