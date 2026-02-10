package com.linlay.springaiagw.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public record AgentDelta(
        String role,
        String content,
        String thinking,
        List<SseChunk.ToolCall> toolCalls,
        List<ToolResult> toolResults,
        String finishReason
) {

    public static AgentDelta content(String content) {
        return new AgentDelta("assistant", content, null, null, null, null);
    }

    public static AgentDelta thinking(String thinking) {
        return new AgentDelta("assistant", null, thinking, null, null, null);
    }

    public static AgentDelta toolCalls(List<SseChunk.ToolCall> toolCalls) {
        return new AgentDelta("assistant", null, null, toolCalls, null, null);
    }

    public static AgentDelta toolResult(String toolId, Object result) {
        return new AgentDelta("assistant", null, null, null, List.of(new ToolResult(toolId, stringifyResult(result))), null);
    }

    public static AgentDelta finish(String finishReason) {
        return new AgentDelta("assistant", null, null, null, null, finishReason);
    }

    public record ToolResult(
            String toolId,
            String result
    ) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String stringifyResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof String value) {
            return value;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            return String.valueOf(result);
        }
    }
}
