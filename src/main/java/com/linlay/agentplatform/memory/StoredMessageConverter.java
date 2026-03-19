package com.linlay.agentplatform.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.util.IdGenerators;
import com.linlay.agentplatform.util.StringHelpers;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracted ChatWindowMemoryStore conversion and normalization helpers.
 */
final class StoredMessageConverter {

    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryProperties properties;

    StoredMessageConverter(ObjectMapper objectMapper, ChatWindowMemoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    List<ChatMemoryTypes.StoredMessage> convertRunMessages(
            String runId,
            List<ChatMemoryTypes.RunMessage> runMessages,
            Set<String> runtimeActionTools,
            TextBlockSequenceState sequenceState
    ) {
        Map<String, ToolIdentity> toolIdentityByCallId = new LinkedHashMap<>();
        List<ChatMemoryTypes.StoredMessage> storedMessages = new ArrayList<>();
        int reasoningSeq = sequenceState == null ? 1 : Math.max(1, sequenceState.nextReasoningSeq());
        int contentSeq = sequenceState == null ? 1 : Math.max(1, sequenceState.nextContentSeq());
        long tsCursor = System.currentTimeMillis();
        for (ChatMemoryTypes.RunMessage message : runMessages) {
            if (message == null || !StringUtils.hasText(message.kind())) {
                continue;
            }
            long ts = message.ts() == null ? tsCursor++ : message.ts();
            ChatMemoryTypes.StoredMessage converted = switch (message.kind().trim().toLowerCase(Locale.ROOT)) {
                case "user_content" -> toUserStoredMessage(message, ts);
                case "assistant_reasoning" -> toAssistantReasoningMessage(runId, message, ts, reasoningSeq++);
                case "assistant_content" -> toAssistantContentMessage(runId, message, ts, contentSeq++);
                case "assistant_tool_call" -> toAssistantToolCallMessage(message, ts, toolIdentityByCallId, runtimeActionTools);
                case "tool_result" -> toToolResultMessage(message, ts, toolIdentityByCallId, runtimeActionTools);
                default -> null;
            };
            if (converted != null) {
                storedMessages.add(converted);
            }
        }
        return storedMessages;
    }

    ChatMessage toChatMessage(ChatMemoryTypes.StoredMessage message) {
        if (message == null || !hasText(message.role)) {
            return null;
        }
        String role = message.role.trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "user" -> toUserMsg(message);
            case "assistant" -> toAssistantMsg(message);
            case "tool" -> toToolMsg(message);
            case "system" -> toSystemMsg(message);
            default -> null;
        };
    }

    ChatMemoryTypes.SystemSnapshot normalizeSystemSnapshot(ChatMemoryTypes.SystemSnapshot source) {
        if (source == null) {
            return null;
        }
        ChatMemoryTypes.SystemSnapshot normalized = objectMapper.convertValue(source, ChatMemoryTypes.SystemSnapshot.class);
        if (normalized == null) {
            return null;
        }
        normalized.model = nullable(normalized.model);
        normalized.messages = normalizeSystemMessages(normalized.messages);
        normalized.tools = normalizeSystemTools(normalized.tools);
        if (normalized.model == null
                && (normalized.messages == null || normalized.messages.isEmpty())
                && (normalized.tools == null || normalized.tools.isEmpty())
                && normalized.stream == null) {
            return null;
        }
        return normalized;
    }

    ChatMemoryTypes.PlanState normalizePlanState(ChatMemoryTypes.PlanState source) {
        if (source == null) {
            return null;
        }
        ChatMemoryTypes.PlanState normalized = objectMapper.convertValue(source, ChatMemoryTypes.PlanState.class);
        if (normalized == null || !hasText(normalized.planId) || normalized.tasks == null || normalized.tasks.isEmpty()) {
            return null;
        }
        List<ChatMemoryTypes.PlanTaskState> tasks = new ArrayList<>();
        for (ChatMemoryTypes.PlanTaskState task : normalized.tasks) {
            if (task == null || !hasText(task.taskId) || !hasText(task.description)) {
                continue;
            }
            ChatMemoryTypes.PlanTaskState item = new ChatMemoryTypes.PlanTaskState();
            item.taskId = task.taskId.trim();
            item.description = task.description.trim();
            item.status = normalizeStatus(task.status);
            tasks.add(item);
        }
        if (tasks.isEmpty()) {
            return null;
        }
        ChatMemoryTypes.PlanState state = new ChatMemoryTypes.PlanState();
        state.planId = normalized.planId.trim();
        state.tasks = List.copyOf(tasks);
        return state;
    }

    Set<String> extractActionToolNames(ChatMemoryTypes.SystemSnapshot systemSnapshot) {
        if (systemSnapshot == null || systemSnapshot.tools == null || systemSnapshot.tools.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (ChatMemoryTypes.SystemToolSnapshot tool : systemSnapshot.tools) {
            if (tool == null || !hasText(tool.type) || !hasText(tool.name)) {
                continue;
            }
            if (!"action".equalsIgnoreCase(tool.type.trim())) {
                continue;
            }
            names.add(normalizeToolName(tool.name));
        }
        return Set.copyOf(names);
    }

    private ChatMemoryTypes.StoredMessage toUserStoredMessage(ChatMemoryTypes.RunMessage message, long ts) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatMemoryTypes.StoredMessage stored = new ChatMemoryTypes.StoredMessage();
        stored.role = "user";
        stored.content = textContent(message.text());
        stored.ts = ts;
        return stored;
    }

    private ChatMemoryTypes.StoredMessage toAssistantReasoningMessage(
            String runId,
            ChatMemoryTypes.RunMessage message,
            long ts,
            int sequence
    ) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatMemoryTypes.StoredMessage stored = new ChatMemoryTypes.StoredMessage();
        stored.role = "assistant";
        stored.reasoningContent = textContent(message.text());
        stored.ts = ts;
        stored.reasoningId = hasText(message.reasoningId())
                ? message.reasoningId().trim()
                : autoReasoningId(runId, sequence);
        stored.msgId = hasText(message.msgId()) ? message.msgId().trim() : null;
        stored.timing = positiveOrNull(message.timing());
        stored.usage = usageOrNull(message.usage());
        return stored;
    }

    private ChatMemoryTypes.StoredMessage toAssistantContentMessage(
            String runId,
            ChatMemoryTypes.RunMessage message,
            long ts,
            int sequence
    ) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatMemoryTypes.StoredMessage stored = new ChatMemoryTypes.StoredMessage();
        stored.role = "assistant";
        stored.content = textContent(message.text());
        stored.ts = ts;
        stored.contentId = hasText(message.contentId())
                ? message.contentId().trim()
                : autoContentId(runId, sequence);
        stored.msgId = hasText(message.msgId()) ? message.msgId().trim() : null;
        stored.timing = positiveOrNull(message.timing());
        stored.usage = usageOrNull(message.usage());
        return stored;
    }

    private ChatMemoryTypes.StoredMessage toAssistantToolCallMessage(
            ChatMemoryTypes.RunMessage message,
            long ts,
            Map<String, ToolIdentity> toolIdentityByCallId,
            Set<String> runtimeActionTools
    ) {
        if (!hasText(message.name()) || !hasText(message.toolCallId()) || !hasText(message.toolArgs())) {
            return null;
        }
        String toolCallId = message.toolCallId().trim();
        String toolName = message.name().trim();

        ToolIdentity identity = toolIdentityByCallId.computeIfAbsent(
                toolCallId,
                key -> createToolIdentity(toolCallId, toolName, message.toolCallType(), runtimeActionTools)
        );

        ChatMemoryTypes.StoredToolCall toolCall = new ChatMemoryTypes.StoredToolCall();
        toolCall.id = toolCallId;
        toolCall.type = hasText(message.toolCallType()) ? message.toolCallType().trim() : "function";
        ChatMemoryTypes.FunctionCall function = new ChatMemoryTypes.FunctionCall();
        function.name = toolName;
        function.arguments = message.toolArgs().trim();
        toolCall.function = function;

        ChatMemoryTypes.StoredMessage stored = new ChatMemoryTypes.StoredMessage();
        stored.role = "assistant";
        stored.toolCalls = List.of(toolCall);
        stored.ts = ts;
        stored.msgId = hasText(message.msgId()) ? message.msgId().trim() : null;
        if (identity.action()) {
            stored.actionId = identity.id();
        } else {
            stored.toolId = identity.id();
        }
        stored.timing = positiveOrNull(message.timing());
        stored.usage = usageOrNull(message.usage());
        return stored;
    }

    private ChatMemoryTypes.StoredMessage toToolResultMessage(
            ChatMemoryTypes.RunMessage message,
            long ts,
            Map<String, ToolIdentity> toolIdentityByCallId,
            Set<String> runtimeActionTools
    ) {
        if (!hasText(message.name()) || !hasText(message.toolCallId())) {
            return null;
        }
        String toolCallId = message.toolCallId().trim();
        String toolName = message.name().trim();

        ToolIdentity identity = toolIdentityByCallId.computeIfAbsent(
                toolCallId,
                key -> createToolIdentity(toolCallId, toolName, null, runtimeActionTools)
        );

        ChatMemoryTypes.StoredMessage stored = new ChatMemoryTypes.StoredMessage();
        stored.role = "tool";
        stored.name = toolName;
        stored.toolCallId = toolCallId;
        stored.content = textContent(defaultString(message.text()));
        stored.ts = ts;
        stored.timing = positiveOrNull(message.timing());
        if (identity.action()) {
            stored.actionId = identity.id();
        } else {
            stored.toolId = identity.id();
        }
        return stored;
    }

    private ChatMessage toUserMsg(ChatMemoryTypes.StoredMessage message) {
        String text = textFromContentParts(message.content);
        return hasText(text) ? new ChatMessage.UserMsg(text) : null;
    }

    private ChatMessage toSystemMsg(ChatMemoryTypes.StoredMessage message) {
        String text = textFromContentParts(message.content);
        return hasText(text) ? new ChatMessage.SystemMsg(text) : null;
    }

    private ChatMessage toAssistantMsg(ChatMemoryTypes.StoredMessage message) {
        if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
            List<ChatMessage.AssistantMsg.ToolCall> toolCalls = new ArrayList<>();
            for (ChatMemoryTypes.StoredToolCall toolCall : message.toolCalls) {
                if (toolCall == null || toolCall.function == null || !hasText(toolCall.id) || !hasText(toolCall.function.name)) {
                    continue;
                }
                String type = hasText(toolCall.type) ? toolCall.type : "function";
                String arguments = hasText(toolCall.function.arguments) ? toolCall.function.arguments : "{}";
                toolCalls.add(new ChatMessage.AssistantMsg.ToolCall(
                        toolCall.id,
                        type,
                        toolCall.function.name,
                        arguments
                ));
            }
            if (!toolCalls.isEmpty()) {
                String content = textFromContentParts(message.content);
                return new ChatMessage.AssistantMsg(defaultString(content), toolCalls);
            }
            return null;
        }

        if (message.reasoningContent != null && !message.reasoningContent.isEmpty()) {
            return null;
        }

        String text = textFromContentParts(message.content);
        return hasText(text) ? new ChatMessage.AssistantMsg(text) : null;
    }

    private ChatMessage toToolMsg(ChatMemoryTypes.StoredMessage message) {
        if (!hasText(message.toolCallId) || !hasText(message.name)) {
            return null;
        }
        String responseData = textFromContentParts(message.content);
        ChatMessage.ToolResultMsg.ToolResponse toolResponse = new ChatMessage.ToolResultMsg.ToolResponse(
                message.toolCallId,
                message.name,
                defaultString(responseData)
        );
        return new ChatMessage.ToolResultMsg(List.of(toolResponse));
    }

    private List<ChatMemoryTypes.SystemMessageSnapshot> normalizeSystemMessages(List<ChatMemoryTypes.SystemMessageSnapshot> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<ChatMemoryTypes.SystemMessageSnapshot> normalized = new ArrayList<>();
        for (ChatMemoryTypes.SystemMessageSnapshot message : messages) {
            if (message == null || !hasText(message.role) || !hasText(message.content)) {
                continue;
            }
            ChatMemoryTypes.SystemMessageSnapshot item = new ChatMemoryTypes.SystemMessageSnapshot();
            item.role = message.role.trim();
            item.content = message.content;
            normalized.add(item);
        }
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    private List<ChatMemoryTypes.SystemToolSnapshot> normalizeSystemTools(List<ChatMemoryTypes.SystemToolSnapshot> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ChatMemoryTypes.SystemToolSnapshot> normalized = new ArrayList<>();
        for (ChatMemoryTypes.SystemToolSnapshot tool : tools) {
            if (tool == null || !hasText(tool.type) || !hasText(tool.name)) {
                continue;
            }
            ChatMemoryTypes.SystemToolSnapshot item = new ChatMemoryTypes.SystemToolSnapshot();
            item.type = tool.type.trim();
            item.name = tool.name.trim();
            item.description = nullable(tool.description);
            item.parameters = tool.parameters == null ? null : new LinkedHashMap<>(tool.parameters);
            normalized.add(item);
        }
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    private ToolIdentity createToolIdentity(String toolCallId, String toolName, String toolType, Set<String> runtimeActionTools) {
        boolean action = "action".equalsIgnoreCase(normalizeType(toolType))
                || isActionTool(toolName)
                || isRuntimeActionTool(runtimeActionTools, toolName);
        String id = hasText(toolCallId) ? toolCallId.trim() : shortId("t", toolCallId);
        return new ToolIdentity(id, action);
    }

    private boolean isActionTool(String toolName) {
        if (!hasText(toolName)) {
            return false;
        }
        String normalized = normalizeToolName(toolName);
        List<String> actionTools = properties.getActionTools();
        if (actionTools == null || actionTools.isEmpty()) {
            return false;
        }
        for (String configured : actionTools) {
            if (hasText(configured) && normalized.equals(normalizeToolName(configured))) {
                return true;
            }
        }
        return false;
    }

    private boolean isRuntimeActionTool(Set<String> runtimeActionTools, String toolName) {
        if (runtimeActionTools == null || runtimeActionTools.isEmpty() || !hasText(toolName)) {
            return false;
        }
        return runtimeActionTools.contains(normalizeToolName(toolName));
    }

    private String normalizeToolName(String toolName) {
        return toolName.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeType(String rawType) {
        if (!hasText(rawType)) {
            return "";
        }
        return rawType.trim().toLowerCase(Locale.ROOT);
    }

    private List<ChatMemoryTypes.ContentPart> textContent(String text) {
        if (!hasText(text)) {
            return null;
        }
        ChatMemoryTypes.ContentPart part = new ChatMemoryTypes.ContentPart();
        part.type = "text";
        part.text = text;
        return List.of(part);
    }

    private String textFromContentParts(List<ChatMemoryTypes.ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMemoryTypes.ContentPart contentPart : contentParts) {
            if (contentPart == null || !hasText(contentPart.text)) {
                continue;
            }
            sb.append(contentPart.text);
        }
        return sb.toString();
    }

    private String autoReasoningId(String runId, int sequence) {
        String normalizedRunId = hasText(runId) ? runId.trim() : toBase36Now();
        return normalizedRunId + "_r_" + Math.max(1, sequence);
    }

    private String autoContentId(String runId, int sequence) {
        String normalizedRunId = hasText(runId) ? runId.trim() : toBase36Now();
        return normalizedRunId + "_c_" + Math.max(1, sequence);
    }

    private String toBase36Now() {
        return Long.toString(System.currentTimeMillis(), 36);
    }

    private String shortId(String prefix, String seed) {
        return IdGenerators.shortHexId(prefix, seed);
    }

    private Map<String, Object> usageOrNull(Map<String, Object> usage) {
        if (usage != null && !usage.isEmpty()) {
            return usage;
        }
        return null;
    }

    private Long positiveOrNull(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private String nullable(String value) {
        return StringHelpers.nullable(value);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private boolean hasText(String value) {
        return StringHelpers.hasText(value);
    }
}
