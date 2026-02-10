package com.linlay.springaiagw.model;

import java.util.List;

public record SseChunk(
        String id,
        String model,
        long created,
        String object,
        List<Choice> choices
) {

    public record Choice(
            int index,
            Delta delta,
            String finishReason
    ) {
    }

    public record Delta(
            String role,
            String content,
            String thinking,
            List<ToolCall> toolCalls
    ) {
    }

    public record ToolCall(
            String id,
            String type,
            Function function
    ) {
    }

    public record Function(
            String name,
            String arguments
    ) {
    }
}
