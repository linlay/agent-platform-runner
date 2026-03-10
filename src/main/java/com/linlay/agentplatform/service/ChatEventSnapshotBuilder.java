package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChatEventSnapshotBuilder {

    private final ToolRegistry toolRegistry;

    ChatEventSnapshotBuilder(ObjectMapper objectMapper, ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    List<Map<String, Object>> buildSnapshotEvents(
            String chatId,
            String chatName,
            String boundAgentKey,
            List<ChatHistoryRunSnapshot> runs
    ) {
        if (runs.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> events = new ArrayList<>();
        long seq = 1L;
        boolean emittedChatStart = false;

        for (ChatHistoryRunSnapshot run : runs) {
            long runStartTs = resolveRunStartTimestamp(run);
            long runEndTs = resolveRunEndTimestamp(run, runStartTs);
            long timestampCursor = runStartTs;
            int reasoningIndex = 0;
            int contentIndex = 0;
            int toolIndex = 0;
            int actionIndex = 0;
            Map<String, IdBinding> bindingByCallId = new LinkedHashMap<>();
            List<String> hiddenToolCallIds = new ArrayList<>();
            List<String> hiddenToolBindingIds = new ArrayList<>();
            List<PersistedChatEvent> persistedEvents = run.persistedEvents().stream()
                    .sorted(Comparator.comparingLong(PersistedChatEvent::timestamp)
                            .thenComparingInt(PersistedChatEvent::lineIndex))
                    .toList();
            int persistedIndex = 0;

            Map<String, Object> requestQueryPayload = buildRequestQueryPayload(chatId, run);
            String runAgentKey = requireRunStartAgentKey(chatId, run.runId(), requestQueryPayload.get("agentKey"), boundAgentKey);
            events.add(event("request.query", timestampCursor, seq++, requestQueryPayload));

            if (!emittedChatStart) {
                timestampCursor = normalizeEventTimestamp(timestampCursor + 1, timestampCursor);
                Map<String, Object> chatStartPayload = new LinkedHashMap<>();
                chatStartPayload.put("chatId", chatId);
                if (StringUtils.hasText(chatName)) {
                    chatStartPayload.put("chatName", chatName);
                }
                events.add(event("chat.start", timestampCursor, seq++, chatStartPayload));
                emittedChatStart = true;
            }

            timestampCursor = normalizeEventTimestamp(timestampCursor + 1, timestampCursor);
            Map<String, Object> runStartPayload = new LinkedHashMap<>();
            runStartPayload.put("runId", run.runId());
            runStartPayload.put("chatId", chatId);
            runStartPayload.put("agentKey", runAgentKey);
            events.add(event("run.start", timestampCursor, seq++, runStartPayload));

            Map<String, Object> planUpdatePayload = planUpdatePayload(run.plan(), chatId);
            if (!planUpdatePayload.isEmpty()) {
                timestampCursor = normalizeEventTimestamp(timestampCursor + 1, timestampCursor);
                events.add(event("plan.update", timestampCursor, seq++, planUpdatePayload));
            }

            while (persistedIndex < persistedEvents.size()
                    && persistedEvents.get(persistedIndex).timestamp() <= timestampCursor) {
                PersistedChatEvent persisted = persistedEvents.get(persistedIndex++);
                long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                timestampCursor = persistedTs;
            }

            for (ChatWindowMemoryStore.StoredMessage message : run.messages()) {
                if (message == null || !StringUtils.hasText(message.role)) {
                    continue;
                }
                long messageTs = normalizeEventTimestamp(resolveMessageTimestamp(message, timestampCursor), timestampCursor);
                while (persistedIndex < persistedEvents.size()
                        && persistedEvents.get(persistedIndex).timestamp() <= messageTs) {
                    PersistedChatEvent persisted = persistedEvents.get(persistedIndex++);
                    long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                    events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                    timestampCursor = persistedTs;
                }
                String role = message.role.trim().toLowerCase();

                if ("assistant".equals(role)) {
                    if (message.reasoningContent != null && !message.reasoningContent.isEmpty()) {
                        String text = textFromContent(message.reasoningContent);
                        if (StringUtils.hasText(text)) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("reasoningId", StringUtils.hasText(message.reasoningId)
                                    ? message.reasoningId
                                    : run.runId() + "_r_" + reasoningIndex++);
                            payload.put("text", text);
                            events.add(event("reasoning.snapshot", messageTs, seq++, payload));
                            timestampCursor = messageTs;
                        }
                    }
                    if (message.content != null && !message.content.isEmpty()) {
                        String text = textFromContent(message.content);
                        if (StringUtils.hasText(text)) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("contentId", StringUtils.hasText(message.contentId)
                                    ? message.contentId
                                    : run.runId() + "_c_" + contentIndex++);
                            payload.put("text", text);
                            events.add(event("content.snapshot", messageTs, seq++, payload));
                            timestampCursor = messageTs;
                        }
                    }
                    if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
                        for (ChatWindowMemoryStore.StoredToolCall toolCall : message.toolCalls) {
                            if (toolCall == null || toolCall.function == null || !StringUtils.hasText(toolCall.function.name)) {
                                continue;
                            }
                            IdBinding binding = resolveBindingForAssistantToolCall(run.runId(), message, toolCall, toolIndex, actionIndex);
                            if (binding.action()) {
                                actionIndex++;
                            } else {
                                toolIndex++;
                            }
                            if (!binding.action() && !isClientVisibleTool(toolCall.function.name)) {
                                if (StringUtils.hasText(toolCall.id)) {
                                    hiddenToolCallIds.add(toolCall.id.trim());
                                }
                                hiddenToolBindingIds.add(binding.id());
                                continue;
                            }
                            if (StringUtils.hasText(toolCall.id)) {
                                bindingByCallId.put(toolCall.id.trim(), binding);
                            }

                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put(binding.action() ? "actionId" : "toolId", binding.id());
                            payload.put(binding.action() ? "actionName" : "toolName", toolCall.function.name);
                            payload.put("arguments", toolCall.function.arguments);

                            if (!binding.action()) {
                                if (StringUtils.hasText(toolCall.type) && !"function".equalsIgnoreCase(toolCall.type)) {
                                    payload.put("toolType", toolCall.type);
                                }
                                putIfNonNull(payload, "toolLabel", resolveToolLabel(toolCall.function.name));
                                putIfNonNull(payload, "toolDescription", resolveToolDescription(toolCall.function.name));
                            } else {
                                payload.put("description", null);
                            }

                            timestampCursor = normalizeEventTimestamp(messageTs, timestampCursor);
                            events.add(event(binding.action() ? "action.snapshot" : "tool.snapshot", timestampCursor, seq++, payload));
                            messageTs = timestampCursor + 1;
                        }
                    }
                    continue;
                }

                if (!"tool".equals(role)) {
                    continue;
                }

                String result = textFromContent(message.content);
                if (!StringUtils.hasText(result)) {
                    result = "";
                }

                if (StringUtils.hasText(message.toolId) && hiddenToolBindingIds.contains(message.toolId.trim())) {
                    continue;
                }
                if (StringUtils.hasText(message.toolCallId) && hiddenToolCallIds.contains(message.toolCallId.trim())) {
                    continue;
                }

                IdBinding binding = resolveBindingForToolResult(run.runId(), message, bindingByCallId, toolIndex, actionIndex);
                if (binding == null) {
                    continue;
                }
                if (!binding.action() && hiddenToolBindingIds.contains(binding.id())) {
                    continue;
                }
                if (!binding.action() && StringUtils.hasText(message.name) && !isClientVisibleTool(message.name)) {
                    continue;
                }
                if (binding.action()) {
                    actionIndex++;
                } else {
                    toolIndex++;
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put(binding.action() ? "actionId" : "toolId", binding.id());
                payload.put("result", result);
                timestampCursor = normalizeEventTimestamp(messageTs, timestampCursor);
                events.add(event(binding.action() ? "action.result" : "tool.result", timestampCursor, seq++, payload));
            }

            while (persistedIndex < persistedEvents.size()) {
                PersistedChatEvent persisted = persistedEvents.get(persistedIndex++);
                long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                timestampCursor = persistedTs;
            }

            timestampCursor = normalizeEventTimestamp(runEndTs + 1, timestampCursor);
            Map<String, Object> runCompletePayload = new LinkedHashMap<>();
            runCompletePayload.put("runId", run.runId());
            runCompletePayload.put("finishReason", "end_turn");
            events.add(event("run.complete", timestampCursor, seq++, runCompletePayload));
        }

        return List.copyOf(events);
    }

    private Map<String, Object> buildRequestQueryPayload(String chatId, ChatHistoryRunSnapshot run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> query = run.query() == null ? Map.of() : run.query();

        Object requestId = query.get("requestId");
        payload.put("requestId", textOrFallback(requestId, run.runId()));
        payload.put("chatId", textOrFallback(query.get("chatId"), chatId));
        payload.put("role", textOrFallback(query.get("role"), "user"));
        payload.put("message", textOrFallback(query.get("message"), firstUserText(run.messages())));
        putIfNonNull(payload, "agentKey", query.get("agentKey"));
        putIfNonNull(payload, "teamId", query.get("teamId"));
        putIfNonNull(payload, "references", query.get("references"));
        putIfNonNull(payload, "params", query.get("params"));
        putIfNonNull(payload, "scene", query.get("scene"));
        putIfNonNull(payload, "stream", query.get("stream"));
        return payload;
    }

    private String requireRunStartAgentKey(String chatId, String runId, Object agentKeyValue, String boundAgentKey) {
        if (agentKeyValue instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        if (StringUtils.hasText(boundAgentKey)) {
            return boundAgentKey.trim();
        }
        throw new IllegalStateException(
                "run.start requires non-blank agentKey in history query for chatId=" + chatId + ", runId=" + runId
        );
    }

    private IdBinding resolveBindingForAssistantToolCall(
            String runId,
            ChatWindowMemoryStore.StoredMessage message,
            ChatWindowMemoryStore.StoredToolCall toolCall,
            int toolIndex,
            int actionIndex
    ) {
        if (StringUtils.hasText(message.actionId)) {
            return new IdBinding(message.actionId.trim(), true);
        }
        if (StringUtils.hasText(message.toolId)) {
            return new IdBinding(message.toolId.trim(), false);
        }
        boolean actionByType = StringUtils.hasText(toolCall.type)
                && "action".equalsIgnoreCase(toolCall.type.trim());
        if (StringUtils.hasText(toolCall.actionId)) {
            return new IdBinding(toolCall.actionId.trim(), true);
        }
        if (StringUtils.hasText(toolCall.toolId)) {
            return new IdBinding(toolCall.toolId.trim(), false);
        }
        if (actionByType && StringUtils.hasText(toolCall.id)) {
            return new IdBinding(toolCall.id.trim(), true);
        }
        if (StringUtils.hasText(toolCall.id)) {
            return new IdBinding(toolCall.id.trim(), false);
        }
        if (actionByType) {
            return new IdBinding(runId + "_action_" + actionIndex, true);
        }
        return new IdBinding(runId + "_tool_" + toolIndex + "_action_" + actionIndex, false);
    }

    private IdBinding resolveBindingForToolResult(
            String runId,
            ChatWindowMemoryStore.StoredMessage message,
            Map<String, IdBinding> bindingByCallId,
            int toolIndex,
            int actionIndex
    ) {
        if (StringUtils.hasText(message.actionId)) {
            return new IdBinding(message.actionId.trim(), true);
        }
        if (StringUtils.hasText(message.toolId)) {
            return new IdBinding(message.toolId.trim(), false);
        }
        if (StringUtils.hasText(message.toolCallId)) {
            IdBinding binding = bindingByCallId.get(message.toolCallId.trim());
            if (binding != null) {
                return binding;
            }
            return new IdBinding(message.toolCallId.trim(), false);
        }
        if (!StringUtils.hasText(message.name)) {
            return null;
        }
        return new IdBinding(runId + "_tool_result_" + toolIndex + "_action_" + actionIndex, false);
    }

    private boolean isClientVisibleTool(String toolName) {
        if (!StringUtils.hasText(toolName) || toolRegistry == null) {
            return true;
        }
        return toolRegistry.descriptor(toolName)
                .map(descriptor -> !Boolean.FALSE.equals(descriptor.clientVisible()))
                .orElse(true);
    }

    private String resolveToolLabel(String toolName) {
        if (!StringUtils.hasText(toolName) || toolRegistry == null) {
            return null;
        }
        String label = toolRegistry.label(toolName);
        return StringUtils.hasText(label) ? label.trim() : null;
    }

    private String resolveToolDescription(String toolName) {
        if (!StringUtils.hasText(toolName) || toolRegistry == null) {
            return null;
        }
        String description = toolRegistry.description(toolName);
        return StringUtils.hasText(description) ? description.trim() : null;
    }

    private String firstUserText(List<ChatWindowMemoryStore.StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (ChatWindowMemoryStore.StoredMessage message : messages) {
            if (message == null || !"user".equalsIgnoreCase(message.role)) {
                continue;
            }
            String text = textFromContent(message.content);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String textFromContent(List<ChatWindowMemoryStore.ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatWindowMemoryStore.ContentPart contentPart : contentParts) {
            if (contentPart == null || !StringUtils.hasText(contentPart.text)) {
                continue;
            }
            text.append(contentPart.text);
        }
        return text.toString();
    }

    private long resolveRunStartTimestamp(ChatHistoryRunSnapshot run) {
        long earliest = Long.MAX_VALUE;
        for (ChatWindowMemoryStore.StoredMessage message : run.messages()) {
            if (message != null && message.ts != null && message.ts > 0 && message.ts < earliest) {
                earliest = message.ts;
            }
        }
        if (earliest != Long.MAX_VALUE) {
            return earliest;
        }
        if (run.updatedAt() > 0) {
            return run.updatedAt();
        }
        return System.currentTimeMillis();
    }

    private long resolveRunEndTimestamp(ChatHistoryRunSnapshot run, long fallbackStart) {
        long latest = Long.MIN_VALUE;
        for (ChatWindowMemoryStore.StoredMessage message : run.messages()) {
            if (message != null && message.ts != null && message.ts > 0 && message.ts > latest) {
                latest = message.ts;
            }
        }
        if (latest != Long.MIN_VALUE) {
            return latest;
        }
        if (run.updatedAt() > 0) {
            return run.updatedAt();
        }
        return fallbackStart;
    }

    private long resolveMessageTimestamp(ChatWindowMemoryStore.StoredMessage message, long fallback) {
        if (message != null && message.ts != null && message.ts > 0) {
            return message.ts;
        }
        return fallback;
    }

    private long normalizeEventTimestamp(long candidate, long previous) {
        if (candidate <= 0) {
            return previous + 1;
        }
        return Math.max(candidate, previous + 1);
    }

    private Map<String, Object> event(String type, long timestamp, long seq, Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("type", type);
        data.put("timestamp", timestamp);
        if (payload != null && !payload.isEmpty()) {
            data.putAll(payload);
        }
        return data;
    }

    private Map<String, Object> planUpdatePayload(
            ChatWindowMemoryStore.PlanSnapshot planSnapshot,
            String chatId
    ) {
        if (planSnapshot == null
                || !StringUtils.hasText(planSnapshot.planId)
                || planSnapshot.tasks == null
                || planSnapshot.tasks.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        for (ChatWindowMemoryStore.PlanTaskSnapshot task : planSnapshot.tasks) {
            if (task == null || !StringUtils.hasText(task.taskId) || !StringUtils.hasText(task.description)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.taskId.trim());
            item.put("description", task.description.trim());
            item.put("status", AgentDelta.normalizePlanTaskStatus(task.status));
            plan.add(item);
        }
        if (plan.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", planSnapshot.planId.trim());
        payload.put("chatId", StringUtils.hasText(chatId) ? chatId : null);
        payload.put("plan", plan);
        return payload;
    }

    private void putIfNonNull(Map<String, Object> node, String key, Object value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private String textOrFallback(Object value, String fallback) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return fallback;
    }

    private record IdBinding(String id, boolean action) {
    }
}
