package com.linlay.agentplatform.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.linlay.agentplatform.chatstorage.ChatStorageTypes;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatDetailResponse(
        String chatId,
        String chatName,
        String chatImageToken,
        List<Map<String, Object>> rawMessages,
        List<Map<String, Object>> events,
        ChatStorageTypes.PlanState plan,
        ChatStorageTypes.ArtifactState artifact,
        List<QueryRequest.Reference> references
) {
}
