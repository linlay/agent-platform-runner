package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.chatstorage.ChatStorageTypes;

import java.util.List;
import java.util.Map;

record ChatHistoryRunSnapshot(
        String runId,
        long updatedAt,
        boolean hidden,
        Map<String, Object> query,
        ChatStorageTypes.SystemSnapshot system,
        ChatStorageTypes.PlanState plan,
        List<ChatStorageTypes.StoredMessage> messages,
        List<PersistedChatEvent> persistedEvents,
        int lineIndex
) {
}
