package com.linlay.agentplatform.service;

import com.linlay.agentplatform.memory.ChatWindowMemoryStore;

import java.util.List;
import java.util.Map;

record ChatHistoryRunSnapshot(
        String runId,
        long updatedAt,
        Map<String, Object> query,
        ChatWindowMemoryStore.SystemSnapshot system,
        ChatWindowMemoryStore.PlanSnapshot plan,
        List<ChatWindowMemoryStore.StoredMessage> messages,
        List<PersistedChatEvent> persistedEvents,
        int lineIndex
) {
}
