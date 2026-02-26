package com.aiagent.agw.sdk.adapter.openai;

import com.aiagent.agw.sdk.model.AgwInput;
import com.aiagent.agw.sdk.model.LlmDelta;
import com.aiagent.agw.sdk.model.ToolCallDelta;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class OpenAiDeltaToAgwInputMapper {

    private final AtomicLong idCounter = new AtomicLong(0);
    private final Map<Integer, String> indexedToolIds = new HashMap<>();
    private String contentId;
    private String reasoningId;

    public List<AgwInput> mapOrEmpty(LlmDelta delta) {
        if (delta == null) {
            return List.of();
        }
        List<AgwInput> inputs = new ArrayList<>();

        if (delta.toolCalls() != null && !delta.toolCalls().isEmpty()) {
            for (int i = 0; i < delta.toolCalls().size(); i++) {
                ToolCallDelta toolCall = delta.toolCalls().get(i);
                String toolId = resolveToolId(toolCall, i);
                inputs.add(new AgwInput.ToolArgs(
                        toolId,
                        toolCall.arguments() == null ? "" : toolCall.arguments(),
                        null,
                        toolCall.name(),
                        toolCall.type(),
                        null,
                        null,
                        null,
                        null
                ));
            }
        }

        if (hasText(delta.reasoning())) {
            if (reasoningId == null) {
                reasoningId = "reasoning_" + idCounter.incrementAndGet();
            }
            inputs.add(new AgwInput.ReasoningDelta(reasoningId, delta.reasoning(), null));
        }

        if (hasText(delta.content())) {
            if (contentId == null) {
                contentId = "content_" + idCounter.incrementAndGet();
            }
            inputs.add(new AgwInput.ContentDelta(contentId, delta.content(), null));
        }

        if (hasText(delta.finishReason())) {
            inputs.add(new AgwInput.RunComplete(delta.finishReason()));
        }

        return inputs;
    }

    public Flux<AgwInput> map(Flux<LlmDelta> deltas) {
        Objects.requireNonNull(deltas, "deltas must not be null");
        return deltas.concatMapIterable(this::mapOrEmpty);
    }

    private String resolveToolId(ToolCallDelta toolCall, int fallbackIndex) {
        if (hasText(toolCall.id())) {
            if (toolCall.index() != null) {
                indexedToolIds.put(toolCall.index(), toolCall.id());
            }
            return toolCall.id();
        }
        if (toolCall.index() != null && indexedToolIds.containsKey(toolCall.index())) {
            return indexedToolIds.get(toolCall.index());
        }
        int effectiveIndex = toolCall.index() != null ? toolCall.index() : fallbackIndex;
        String generated = "tool_" + idCounter.incrementAndGet();
        indexedToolIds.put(effectiveIndex, generated);
        return generated;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
