package com.linlay.agentplatform.chatstorage;

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

import static com.linlay.agentplatform.util.StringHelpers.hasText;

/**
 * Extracted ChatStorageStore conversion and normalization helpers.
 */
final class StoredMessageConverter {

    private final ObjectMapper objectMapper;
    private final ChatStorageProperties properties;

    StoredMessageConverter(ObjectMapper objectMapper, ChatStorageProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    List<ChatStorageTypes.StoredMessage> convertRunMessages(
            String runId,
            List<ChatStorageTypes.RunMessage> runMessages,
            Set<String> runtimeActionTools,
            TextBlockSequenceState sequenceState
    ) {
        Map<String, ToolIdentity> toolIdentityByCallId = new LinkedHashMap<>();
        List<ChatStorageTypes.StoredMessage> storedMessages = new ArrayList<>();
        int reasoningSeq = sequenceState == null ? 1 : Math.max(1, sequenceState.nextReasoningSeq());
        int contentSeq = sequenceState == null ? 1 : Math.max(1, sequenceState.nextContentSeq());
        long tsCursor = System.currentTimeMillis();
        for (ChatStorageTypes.RunMessage message : runMessages) {
            if (message == null || !StringUtils.hasText(message.kind())) {
                continue;
            }
            long ts = message.ts() == null ? tsCursor++ : message.ts();
            ChatStorageTypes.StoredMessage converted = switch (message.kind().trim().toLowerCase(Locale.ROOT)) {
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

    ChatMessage toChatMessage(ChatStorageTypes.StoredMessage message) {
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

    ChatStorageTypes.SystemSnapshot normalizeSystemSnapshot(ChatStorageTypes.SystemSnapshot source) {
        if (source == null) {
            return null;
        }
        ChatStorageTypes.SystemSnapshot normalized = objectMapper.convertValue(source, ChatStorageTypes.SystemSnapshot.class);
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

    ChatStorageTypes.PlanState normalizePlanState(ChatStorageTypes.PlanState source) {
        if (source == null) {
            return null;
        }
        ChatStorageTypes.PlanState normalized = objectMapper.convertValue(source, ChatStorageTypes.PlanState.class);
        if (normalized == null || !hasText(normalized.planId) || normalized.tasks == null || normalized.tasks.isEmpty()) {
            return null;
        }
        List<ChatStorageTypes.PlanTaskState> tasks = new ArrayList<>();
        for (ChatStorageTypes.PlanTaskState task : normalized.tasks) {
            if (task == null || !hasText(task.taskId) || !hasText(task.description)) {
                continue;
            }
            ChatStorageTypes.PlanTaskState item = new ChatStorageTypes.PlanTaskState();
            item.taskId = task.taskId.trim();
            item.description = task.description.trim();
            item.status = normalizeStatus(task.status);
            tasks.add(item);
        }
        if (tasks.isEmpty()) {
            return null;
        }
        ChatStorageTypes.PlanState state = new ChatStorageTypes.PlanState();
        state.planId = normalized.planId.trim();
        state.tasks = List.copyOf(tasks);
        return state;
    }

    ChatStorageTypes.ArtifactState normalizeArtifactState(ChatStorageTypes.ArtifactState source) {
        if (source == null || source.items == null || source.items.isEmpty()) {
            return null;
        }
        ChatStorageTypes.ArtifactState normalized = objectMapper.convertValue(source, ChatStorageTypes.ArtifactState.class);
        if (normalized == null || normalized.items == null || normalized.items.isEmpty()) {
            return null;
        }
        List<ChatStorageTypes.ArtifactItemState> items = new ArrayList<>();
        for (ChatStorageTypes.ArtifactItemState item : normalized.items) {
            if (item == null || !hasText(item.artifactId) || !hasText(item.type) || !hasText(item.name) || !hasText(item.url)) {
                continue;
            }
            ChatStorageTypes.ArtifactItemState normalizedItem = new ChatStorageTypes.ArtifactItemState();
            normalizedItem.artifactId = item.artifactId.trim();
            normalizedItem.type = item.type.trim();
            normalizedItem.name = item.name.trim();
            normalizedItem.mimeType = nullable(item.mimeType);
            normalizedItem.sizeBytes = item.sizeBytes;
            normalizedItem.url = item.url.trim();
            normalizedItem.sha256 = nullable(item.sha256);
            items.add(normalizedItem);
        }
        if (items.isEmpty()) {
            return null;
        }
        ChatStorageTypes.ArtifactState state = new ChatStorageTypes.ArtifactState();
        state.items = List.copyOf(items);
        return state;
    }

    Set<String> extractActionToolNames(ChatStorageTypes.SystemSnapshot systemSnapshot) {
        if (systemSnapshot == null || systemSnapshot.tools == null || systemSnapshot.tools.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (ChatStorageTypes.SystemToolSnapshot tool : systemSnapshot.tools) {
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

    private ChatStorageTypes.StoredMessage toUserStoredMessage(ChatStorageTypes.RunMessage message, long ts) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatStorageTypes.StoredMessage stored = new ChatStorageTypes.StoredMessage();
        stored.role = "user";
        stored.content = textContent(message.text());
        stored.ts = ts;
        return stored;
    }

    private ChatStorageTypes.StoredMessage toAssistantReasoningMessage(
            String runId,
            ChatStorageTypes.RunMessage message,
            long ts,
            int sequence
    ) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatStorageTypes.StoredMessage stored = new ChatStorageTypes.StoredMessage();
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

    private ChatStorageTypes.StoredMessage toAssistantContentMessage(
            String runId,
            ChatStorageTypes.RunMessage message,
            long ts,
            int sequence
    ) {
        if (!StringUtils.hasText(message.text())) {
            return null;
        }
        ChatStorageTypes.StoredMessage stored = new ChatStorageTypes.StoredMessage();
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

    private ChatStorageTypes.StoredMessage toAssistantToolCallMessage(
            ChatStorageTypes.RunMessage message,
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

        ChatStorageTypes.StoredToolCall toolCall = new ChatStorageTypes.StoredToolCall();
        toolCall.id = toolCallId;
        toolCall.type = hasText(message.toolCallType()) ? message.toolCallType().trim() : "function";
        ChatStorageTypes.FunctionCall function = new ChatStorageTypes.FunctionCall();
        function.name = toolName;
        function.arguments = message.toolArgs().trim();
        toolCall.function = function;

        ChatStorageTypes.StoredMessage stored = new ChatStorageTypes.StoredMessage();
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

    private ChatStorageTypes.StoredMessage toToolResultMessage(
            ChatStorageTypes.RunMessage message,
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

        ChatStorageTypes.StoredMessage stored = new ChatStorageTypes.StoredMessage();
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

    private ChatMessage toUserMsg(ChatStorageTypes.StoredMessage message) {
        String text = textFromContentParts(message.content);
        return hasText(text) ? new ChatMessage.UserMsg(text) : null;
    }

    private ChatMessage toSystemMsg(ChatStorageTypes.StoredMessage message) {
        String text = textFromContentParts(message.content);
        return hasText(text) ? new ChatMessage.SystemMsg(text) : null;
    }

    private ChatMessage toAssistantMsg(ChatStorageTypes.StoredMessage message) {
        if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
            List<ChatMessage.AssistantMsg.ToolCall> toolCalls = new ArrayList<>();
            for (ChatStorageTypes.StoredToolCall toolCall : message.toolCalls) {
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

    private ChatMessage toToolMsg(ChatStorageTypes.StoredMessage message) {
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

    private List<ChatStorageTypes.SystemMessageSnapshot> normalizeSystemMessages(List<ChatStorageTypes.SystemMessageSnapshot> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<ChatStorageTypes.SystemMessageSnapshot> normalized = new ArrayList<>();
        for (ChatStorageTypes.SystemMessageSnapshot message : messages) {
            if (message == null || !hasText(message.role) || !hasText(message.content)) {
                continue;
            }
            ChatStorageTypes.SystemMessageSnapshot item = new ChatStorageTypes.SystemMessageSnapshot();
            item.role = message.role.trim();
            item.content = message.content;
            normalized.add(item);
        }
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    private List<ChatStorageTypes.SystemToolSnapshot> normalizeSystemTools(List<ChatStorageTypes.SystemToolSnapshot> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ChatStorageTypes.SystemToolSnapshot> normalized = new ArrayList<>();
        for (ChatStorageTypes.SystemToolSnapshot tool : tools) {
            if (tool == null || !hasText(tool.type) || !hasText(tool.name)) {
                continue;
            }
            ChatStorageTypes.SystemToolSnapshot item = new ChatStorageTypes.SystemToolSnapshot();
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

    private List<ChatStorageTypes.ContentPart> textContent(String text) {
        if (!hasText(text)) {
            return null;
        }
        ChatStorageTypes.ContentPart part = new ChatStorageTypes.ContentPart();
        part.type = "text";
        part.text = text;
        return List.of(part);
    }

    private String textFromContentParts(List<ChatStorageTypes.ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatStorageTypes.ContentPart contentPart : contentParts) {
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
}
