package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.memory.ChatMemoryTypes;

import java.util.List;
import java.util.Map;

record ChatHistoryRunSnapshot(
        String runId,
        long updatedAt,
        boolean hidden,
        Map<String, Object> query,
        ChatMemoryTypes.SystemSnapshot system,
        ChatMemoryTypes.PlanState plan,
        List<ChatMemoryTypes.StoredMessage> messages,
        List<PersistedChatEvent> persistedEvents,
        int lineIndex
) {
}
