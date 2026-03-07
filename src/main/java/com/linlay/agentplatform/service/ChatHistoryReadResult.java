package com.linlay.agentplatform.service;

import com.linlay.agentplatform.model.api.QueryRequest;

import java.util.LinkedHashMap;
import java.util.List;

record ChatHistoryReadResult(
        List<ChatHistoryRunSnapshot> runs,
        LinkedHashMap<String, QueryRequest.Reference> references
) {
}
