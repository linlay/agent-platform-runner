package com.linlay.springaiagw.service;

import com.aiagent.agw.sdk.model.AgwInput;
import com.aiagent.agw.sdk.model.ToolCallDelta;
import com.linlay.springaiagw.model.agw.AgentDelta;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AgentDeltaToAgwInputMapper {

    private final AtomicLong idCounter = new AtomicLong(0);
    private final Map<Integer, String> indexedToolIds = new HashMap<>();
    private final Map<String, AtomicInteger> toolArgChunkCounters = new HashMap<>();
    private final String reasoningId;
    private final String contentId;

    public AgentDeltaToAgwInputMapper() {
        this(null);
    }

    public AgentDeltaToAgwInputMapper(String runId) {
        String prefix = hasText(runId) ? runId : "run";
        this.reasoningId = prefix + "_reasoning_1";
        this.contentId = prefix + "_content_1";
    }

    public Flux<AgwInput> map(Flux<AgentDelta> deltas) {
        Objects.requireNonNull(deltas, "deltas must not be null");
        return deltas.concatMapIterable(this::mapOrEmpty);
    }

    public List<AgwInput> mapOrEmpty(AgentDelta delta) {
        if (delta == null) {
            return List.of();
        }

        List<AgwInput> inputs = new ArrayList<>();

        if (hasText(delta.thinking())) {
            inputs.add(new AgwInput.ReasoningDelta(reasoningId, delta.thinking(), null));
        }

        if (hasText(delta.content())) {
            inputs.add(new AgwInput.ContentDelta(contentId, delta.content(), null));
        }

        if (delta.toolCalls() != null && !delta.toolCalls().isEmpty()) {
            for (int i = 0; i < delta.toolCalls().size(); i++) {
                ToolCallDelta toolCall = delta.toolCalls().get(i);
                if (toolCall == null) {
                    continue;
                }
                String toolId = resolveToolId(toolCall, i);
                int chunkIndex = toolArgChunkCounters
                        .computeIfAbsent(toolId, key -> new AtomicInteger(0))
                        .getAndIncrement();
                inputs.add(new AgwInput.ToolArgs(
                        toolId,
                        toolCall.arguments() == null ? "" : toolCall.arguments(),
                        null,
                        toolCall.name(),
                        toolCall.type(),
                        null,
                        null,
                        null,
                        chunkIndex
                ));
            }
        }

        if (delta.toolResults() != null && !delta.toolResults().isEmpty()) {
            for (AgentDelta.ToolResult toolResult : delta.toolResults()) {
                if (toolResult == null || !hasText(toolResult.toolId())) {
                    continue;
                }
                inputs.add(new AgwInput.ToolResult(
                        toolResult.toolId(),
                        toolResult.result() == null ? "null" : toolResult.result()
                ));
            }
        }

        if (hasText(delta.finishReason())) {
            inputs.add(new AgwInput.RunComplete(delta.finishReason()));
        }

        return inputs;
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
