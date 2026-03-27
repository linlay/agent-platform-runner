package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.chatstorage.ChatStorageTypes;
import com.linlay.agentplatform.chatstorage.ChatStorageStore;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.linlay.agentplatform.util.IdGenerators;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class TurnTraceWriter {

    private final ChatStorageStore chatWindowMemoryStore;
    private final Supplier<ChatStorageTypes.SystemSnapshot> systemSnapshotSupplier;
    private final AgentRequest request;
    private final String runId;
    private ChatStorageTypes.SystemSnapshot lastWrittenSystem;
    private StepAccumulator currentStep;
    private int seqCounter;
    private boolean queryLineWritten;
    private ChatStorageTypes.PlanState latestPlan;

    public TurnTraceWriter(
            ChatStorageStore chatWindowMemoryStore,
            Supplier<ChatStorageTypes.SystemSnapshot> systemSnapshotSupplier,
            AgentRequest request,
            String runId,
            ChatStorageTypes.SystemSnapshot lastWrittenSystem
    ) {
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.systemSnapshotSupplier = Objects.requireNonNull(systemSnapshotSupplier);
        this.request = Objects.requireNonNull(request);
        this.runId = runId;
        this.lastWrittenSystem = lastWrittenSystem;
    }

    public void capture(AgentDelta delta) {
        if (delta == null) {
            return;
        }
        long now = System.currentTimeMillis();

        if (delta.stageMarker() != null) {
            String marker = delta.stageMarker().trim();
            String newStage = parseStage(marker);
            String newTaskId = parseTaskId(marker);
            if ("plan".equals(newStage) && currentStep != null && "plan".equals(currentStep.stage)) {
                return;
            }

            flushCurrentStep();
            currentStep = new StepAccumulator(newStage, newTaskId);
            return;
        }

        if (currentStep == null) {
            currentStep = new StepAccumulator("oneshot", null);
        }

        if (StringUtils.hasText(delta.reasoning())) {
            if (currentStep.needNewMsgId) {
                currentStep.currentMsgId = StepAccumulator.generateMsgId();
                currentStep.needNewMsgId = false;
            }
            currentStep.flushAssistantContent(now);
            currentStep.rotateReasoningBlockIfNeeded(delta.reasoningId(), now);
            if (currentStep.pendingReasoningStartedAt <= 0) {
                currentStep.pendingReasoningStartedAt = now;
            }
            if (!StringUtils.hasText(currentStep.pendingReasoningId)) {
                currentStep.pendingReasoningId = delta.reasoningId();
            }
            currentStep.pendingReasoning.append(delta.reasoning());
        }

        if (StringUtils.hasText(delta.content())) {
            if (currentStep.needNewMsgId) {
                currentStep.currentMsgId = StepAccumulator.generateMsgId();
                currentStep.needNewMsgId = false;
            }
            currentStep.flushReasoning(now);
            currentStep.rotateContentBlockIfNeeded(delta.contentId(), now);
            if (currentStep.pendingAssistantStartedAt <= 0) {
                currentStep.pendingAssistantStartedAt = now;
            }
            if (!StringUtils.hasText(currentStep.pendingAssistantContentId)) {
                currentStep.pendingAssistantContentId = delta.contentId();
            }
            currentStep.pendingAssistant.append(delta.content());
        }

        if (StringUtils.hasText(delta.userMessage())) {
            currentStep.appendUserMessage(delta.userMessage(), now);
        }

        if (delta.toolCalls() != null && !delta.toolCalls().isEmpty()) {
            currentStep.flushReasoning(now);
            currentStep.flushAssistantContent(now);
            for (ToolCallDelta toolCall : delta.toolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                String toolCallId = StringUtils.hasText(toolCall.id()) ? toolCall.id() : IdGenerators.toolCallId();
                ToolTrace trace = currentStep.toolByCallId.computeIfAbsent(toolCallId, ToolTrace::new);
                if (trace.firstSeenAt <= 0) {
                    trace.firstSeenAt = now;
                }
                if (StringUtils.hasText(toolCall.name())) {
                    trace.toolName = toolCall.name();
                }
                if (StringUtils.hasText(toolCall.type())) {
                    trace.toolType = toolCall.type();
                }
                if (StringUtils.hasText(toolCall.arguments())) {
                    trace.appendArguments(toolCall.arguments());
                }
            }
        }

        if (delta.toolResults() != null && !delta.toolResults().isEmpty()) {
            currentStep.flushReasoning(now);
            currentStep.flushAssistantContent(now);
            for (AgentDelta.ToolResult toolResult : delta.toolResults()) {
                if (toolResult == null || !StringUtils.hasText(toolResult.toolId())) {
                    continue;
                }
                ToolTrace trace = currentStep.toolByCallId.computeIfAbsent(toolResult.toolId(), ToolTrace::new);
                if (trace.firstSeenAt <= 0) {
                    trace.firstSeenAt = now;
                }
                trace.resultAt = now;
                currentStep.appendAssistantToolCallIfNeeded(trace, now);
                String result = StringUtils.hasText(toolResult.result()) ? toolResult.result() : "null";
                currentStep.orderedMessages.add(ChatStorageTypes.RunMessage.toolResult(
                        trace.toolName,
                        trace.toolCallId,
                        result,
                        now,
                        durationOrNull(trace.firstSeenAt, now)
                ));
            }
            currentStep.needNewMsgId = true;
        }

        if (delta.planUpdate() != null) {
            latestPlan = toPlanState(delta.planUpdate());
            if (currentStep != null) {
                currentStep.plan = latestPlan;
            }
        }

        if (delta.usage() != null && !delta.usage().isEmpty() && currentStep != null) {
            currentStep.capturedUsage = delta.usage();
        }
    }

    public void finalFlush() {
        flushCurrentStep();
    }

    private void flushCurrentStep() {
        if (currentStep == null || currentStep.isEmpty()) {
            return;
        }
        if (chatWindowMemoryStore == null || !StringUtils.hasText(request.chatId())) {
            currentStep = null;
            return;
        }

        if (!queryLineWritten) {
            chatWindowMemoryStore.appendQueryLine(request.chatId(), runId, request.query());
            queryLineWritten = true;
        }

        seqCounter++;

        List<ChatStorageTypes.RunMessage> stepMessages = new ArrayList<>();
        if (seqCounter == 1 && StringUtils.hasText(request.message())) {
            stepMessages.add(ChatStorageTypes.RunMessage.user(request.message(), System.currentTimeMillis()));
        }
        stepMessages.addAll(currentStep.runMessages());
        if (stepMessages.isEmpty()) {
            currentStep = null;
            return;
        }

        ChatStorageTypes.SystemSnapshot stepSystem = null;
        ChatStorageTypes.SystemSnapshot currentSystem = systemSnapshotSupplier.get();
        if (seqCounter == 1) {
            stepSystem = currentSystem;
        } else if (currentSystem != null && (lastWrittenSystem == null
                || !chatWindowMemoryStore.isSameSystem(lastWrittenSystem, currentSystem))) {
            stepSystem = currentSystem;
        }

        chatWindowMemoryStore.appendStepLine(
                request.chatId(),
                runId,
                currentStep.stage,
                seqCounter,
                currentStep.taskId,
                stepSystem,
                currentStep.plan,
                stepMessages
        );

        if (stepSystem != null) {
            lastWrittenSystem = stepSystem;
        }
        currentStep = null;
    }

    private static Long durationOrNull(long startTs, long endTs) {
        if (startTs <= 0 || endTs < startTs) {
            return null;
        }
        return endTs - startTs;
    }

    private static String parseStage(String marker) {
        if (marker.startsWith("react-step-")) {
            return "react";
        }
        if (marker.equals("plan-draft") || marker.equals("plan-generate")) {
            return "plan";
        }
        if (marker.startsWith("execute-task-")) {
            return "execute";
        }
        if (marker.equals("summary")) {
            return "summary";
        }
        return marker;
    }

    private static String parseTaskId(String marker) {
        if (marker.startsWith("execute-task-")) {
            return null;
        }
        return null;
    }

    private static ChatStorageTypes.PlanState toPlanState(AgentDelta.PlanUpdate planUpdate) {
        if (planUpdate == null || !StringUtils.hasText(planUpdate.planId()) || planUpdate.plan() == null || planUpdate.plan().isEmpty()) {
            return null;
        }
        List<ChatStorageTypes.PlanTaskState> tasks = new ArrayList<>();
        for (AgentDelta.PlanTask task : planUpdate.plan()) {
            if (task == null || !StringUtils.hasText(task.taskId()) || !StringUtils.hasText(task.description())) {
                continue;
            }
            ChatStorageTypes.PlanTaskState item = new ChatStorageTypes.PlanTaskState();
            item.taskId = task.taskId().trim();
            item.description = task.description().trim();
            item.status = AgentDelta.normalizePlanTaskStatus(task.status());
            tasks.add(item);
        }
        if (tasks.isEmpty()) {
            return null;
        }
        ChatStorageTypes.PlanState state = new ChatStorageTypes.PlanState();
        state.planId = planUpdate.planId().trim();
        state.tasks = List.copyOf(tasks);
        return state;
    }
}
