package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.model.AgentDelta;
import org.springframework.util.StringUtils;

import java.util.Objects;

public final class TextBlockIdAssigner {

    private final String runPrefix;
    private int reasoningSeq;
    private int contentSeq;
    private String activeReasoningId;
    private String activeContentId;

    public TextBlockIdAssigner(String runId) {
        this.runPrefix = StringUtils.hasText(runId) ? runId.trim() : "run";
    }

    public AgentDelta assign(AgentDelta delta) {
        if (delta == null) {
            return null;
        }
        if (StringUtils.hasText(delta.stageMarker())) {
            closeTextBlocks();
            return delta;
        }

        AgentDelta assigned = delta;
        if (StringUtils.hasText(delta.reasoning())) {
            closeContentBlock();
            String reasoningId = StringUtils.hasText(delta.reasoningId())
                    ? delta.reasoningId().trim()
                    : openReasoningBlockIfNeeded();
            activeReasoningId = reasoningId;
            if (!Objects.equals(reasoningId, delta.reasoningId())) {
                assigned = assigned.withReasoningId(reasoningId);
            }
        }

        if (StringUtils.hasText(delta.content())) {
            closeReasoningBlock();
            String contentId = StringUtils.hasText(delta.contentId())
                    ? delta.contentId().trim()
                    : openContentBlockIfNeeded();
            activeContentId = contentId;
            if (!Objects.equals(contentId, assigned.contentId())) {
                assigned = assigned.withContentId(contentId);
            }
        }

        if (hasNonTextPayload(delta)) {
            closeTextBlocks();
        }
        return assigned;
    }

    private boolean hasNonTextPayload(AgentDelta delta) {
        return delta.taskLifecycle() != null
                || (delta.toolCalls() != null && !delta.toolCalls().isEmpty())
                || (delta.toolEnds() != null && !delta.toolEnds().isEmpty())
                || (delta.toolResults() != null && !delta.toolResults().isEmpty())
                || (delta.artifactPublishes() != null && !delta.artifactPublishes().isEmpty())
                || delta.planUpdate() != null
                || delta.requestSubmit() != null
                || StringUtils.hasText(delta.finishReason());
    }

    private String openReasoningBlockIfNeeded() {
        if (!StringUtils.hasText(activeReasoningId)) {
            reasoningSeq++;
            activeReasoningId = runPrefix + "_r_" + reasoningSeq;
        }
        return activeReasoningId;
    }

    private String openContentBlockIfNeeded() {
        if (!StringUtils.hasText(activeContentId)) {
            contentSeq++;
            activeContentId = runPrefix + "_c_" + contentSeq;
        }
        return activeContentId;
    }

    private void closeReasoningBlock() {
        activeReasoningId = null;
    }

    private void closeContentBlock() {
        activeContentId = null;
    }

    private void closeTextBlocks() {
        closeReasoningBlock();
        closeContentBlock();
    }
}
