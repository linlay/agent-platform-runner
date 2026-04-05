package com.linlay.agentplatform.chat.history;

import com.linlay.agentplatform.chat.event.PersistedChatEvent;
import com.linlay.agentplatform.chat.storage.ChatStorageTypes;

import java.util.List;
import java.util.Map;

public record ChatHistoryRunSnapshot(
        String runId,
        long updatedAt,
        boolean hidden,
        Map<String, Object> query,
        ChatStorageTypes.SystemSnapshot system,
        ChatStorageTypes.PlanState plan,
        ChatStorageTypes.ArtifactState artifacts,
        List<ChatStorageTypes.StoredMessage> messages,
        List<PersistedChatEvent> persistedEvents,
        int lineIndex
) {
}
