package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.chatstorage.ChatStorageTypes;
import com.linlay.agentplatform.util.IdGenerators;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StepAccumulator {

    final String stage;
    final String taskId;
    final StringBuilder pendingReasoning = new StringBuilder();
    String pendingReasoningId;
    long pendingReasoningStartedAt;
    final StringBuilder pendingAssistant = new StringBuilder();
    String pendingAssistantContentId;
    long pendingAssistantStartedAt;
    final List<ChatStorageTypes.RunMessage> orderedMessages = new ArrayList<>();
    final Map<String, ToolTrace> toolByCallId = new LinkedHashMap<>();
    ChatStorageTypes.PlanState plan;
    Map<String, Object> capturedUsage;
    String currentMsgId;
    boolean needNewMsgId;

    StepAccumulator(String stage, String taskId) {
        this.stage = stage;
        this.taskId = taskId;
        this.currentMsgId = generateMsgId();
    }

    static String generateMsgId() {
        return IdGenerators.shortHexId("m");
    }

    boolean isEmpty() {
        return pendingReasoning.isEmpty()
                && pendingAssistant.isEmpty()
                && orderedMessages.isEmpty()
                && toolByCallId.isEmpty();
    }

    List<ChatStorageTypes.RunMessage> runMessages() {
        long now = System.currentTimeMillis();
        flushReasoning(now);
        flushAssistantContent(now);
        toolByCallId.values().forEach(toolTrace -> appendAssistantToolCallIfNeeded(
                toolTrace,
                toolTrace.resultAt > 0 ? toolTrace.resultAt : now
        ));
        if (capturedUsage != null && !capturedUsage.isEmpty()) {
            for (int i = orderedMessages.size() - 1; i >= 0; i--) {
                ChatStorageTypes.RunMessage msg = orderedMessages.get(i);
                if ("assistant".equals(msg.role())) {
                    orderedMessages.set(i, withUsage(msg, capturedUsage));
                    break;
                }
            }
        }
        return List.copyOf(orderedMessages);
    }

    void appendAssistantToolCallIfNeeded(ToolTrace trace, long ts) {
        if (trace == null || trace.recorded) {
            return;
        }
        if (!StringUtils.hasText(trace.toolName)) {
            return;
        }
        orderedMessages.add(ChatStorageTypes.RunMessage.assistantToolCall(
                trace.toolName,
                trace.toolCallId,
                trace.toolType,
                trace.arguments(),
                currentMsgId,
                ts,
                trace.firstSeenAt > 0 && (trace.resultAt > 0 ? trace.resultAt : ts) >= trace.firstSeenAt
                        ? (trace.resultAt > 0 ? trace.resultAt : ts) - trace.firstSeenAt
                        : null,
                null
        ));
        trace.recorded = true;
    }

    void flushReasoning(long now) {
        if (!StringUtils.hasText(pendingReasoning)) {
            return;
        }
        long startedAt = pendingReasoningStartedAt > 0 ? pendingReasoningStartedAt : now;
        orderedMessages.add(ChatStorageTypes.RunMessage.assistantReasoning(
                pendingReasoning.toString(),
                pendingReasoningId,
                currentMsgId,
                startedAt,
                startedAt > 0 && now >= startedAt ? now - startedAt : null,
                null
        ));
        pendingReasoning.setLength(0);
        pendingReasoningId = null;
        pendingReasoningStartedAt = 0L;
    }

    void flushAssistantContent(long now) {
        if (!StringUtils.hasText(pendingAssistant)) {
            return;
        }
        long startedAt = pendingAssistantStartedAt > 0 ? pendingAssistantStartedAt : now;
        orderedMessages.add(ChatStorageTypes.RunMessage.assistantContent(
                pendingAssistant.toString(),
                pendingAssistantContentId,
                currentMsgId,
                startedAt,
                startedAt > 0 && now >= startedAt ? now - startedAt : null,
                null
        ));
        pendingAssistant.setLength(0);
        pendingAssistantContentId = null;
        pendingAssistantStartedAt = 0L;
    }

    void appendUserMessage(String text, long now) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        flushReasoning(now);
        flushAssistantContent(now);
        orderedMessages.add(ChatStorageTypes.RunMessage.user(text, now));
        needNewMsgId = true;
    }

    void rotateReasoningBlockIfNeeded(String nextReasoningId, long now) {
        if (!StringUtils.hasText(pendingReasoning) || !StringUtils.hasText(pendingReasoningId)) {
            return;
        }
        if (!Objects.equals(pendingReasoningId, nextReasoningId)) {
            flushReasoning(now);
        }
    }

    void rotateContentBlockIfNeeded(String nextContentId, long now) {
        if (!StringUtils.hasText(pendingAssistant) || !StringUtils.hasText(pendingAssistantContentId)) {
            return;
        }
        if (!Objects.equals(pendingAssistantContentId, nextContentId)) {
            flushAssistantContent(now);
        }
    }

    private static ChatStorageTypes.RunMessage withUsage(
            ChatStorageTypes.RunMessage original,
            Map<String, Object> usage
    ) {
        return new ChatStorageTypes.RunMessage(
                original.role(),
                original.kind(),
                original.text(),
                original.name(),
                original.toolCallId(),
                original.toolCallType(),
                original.toolArgs(),
                original.reasoningId(),
                original.contentId(),
                original.msgId(),
                original.ts(),
                original.timing(),
                usage
        );
    }
}
