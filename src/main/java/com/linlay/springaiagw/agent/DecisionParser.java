package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 LLM 返回的 JSON 决策结构（ReactDecision / PlainDecision / PlannedToolCall）。
 */
class DecisionParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    DecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ReactDecision parseReactDecision(String rawDecision) {
        JsonNode root = readJsonObject(rawDecision);
        if (root == null || !root.isObject()) {
            return new ReactDecision(
                    "RE-ACT 输出无法解析为 JSON，转为直接生成最终回答。",
                    null,
                    true
            );
        }

        String thinking = normalize(root.path("thinking").asText(), "");
        boolean done = root.path("done").asBoolean(false);
        String finalAnswer = normalize(root.path("finalAnswer").asText(), "");
        if (!finalAnswer.isBlank() && !"null".equalsIgnoreCase(finalAnswer)) {
            done = true;
        }

        PlannedToolCall action = null;
        JsonNode actionNode = root.path("action");
        if (actionNode.isObject()) {
            action = readPlannedToolCall(actionNode);
        }
        if (done) {
            action = null;
        }
        return new ReactDecision(thinking, action, done);
    }
    PlainDecision parsePlainDecision(String rawDecision) {
        JsonNode root = readJsonObject(rawDecision);
        if (root == null || !root.isObject()) {
            return new PlainDecision(
                    "PLAIN 决策输出无法解析为 JSON，转为直接回答。",
                    null,
                    false
            );
        }

        String thinking = normalize(root.path("thinking").asText(), "");
        PlannedToolCall toolCall = null;

        JsonNode toolCallNode = root.path("toolCall");
        if (toolCallNode.isObject()) {
            toolCall = readPlannedToolCall(toolCallNode);
        }
        if (toolCall == null) {
            JsonNode actionNode = root.path("action");
            if (actionNode.isObject()) {
                toolCall = readPlannedToolCall(actionNode);
            }
        }
        if (toolCall == null) {
            JsonNode toolCallsNode = root.path("toolCalls");
            if (toolCallsNode.isArray()) {
                for (JsonNode callNode : toolCallsNode) {
                    toolCall = readPlannedToolCall(callNode);
                    if (toolCall != null) {
                        break;
                    }
                }
            }
        }
        return new PlainDecision(thinking, toolCall, true);
    }

    PlannedToolCall readPlannedToolCall(JsonNode callNode) {
        String toolName = DecisionChunkHandler.normalizeToolName(callNode.path("name").asText());
        if (toolName.isBlank()) {
            return null;
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        JsonNode argumentsNode = callNode.path("arguments");
        if (argumentsNode.isObject()) {
            Map<String, Object> converted = objectMapper.convertValue(argumentsNode, MAP_TYPE);
            if (converted != null) {
                arguments.putAll(converted);
            }
        }

        String callId = normalize(callNode.path("id").asText(), "");
        if (callId.isBlank()) {
            callId = normalize(callNode.path("toolCallId").asText(), "");
        }
        return new PlannedToolCall(toolName, arguments, callId.isBlank() ? null : callId);
    }

    List<String> readTextArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String text = normalize(item.asText(), "");
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    JsonNode readJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.substring(3, normalized.length() - 3).trim();
            if (normalized.startsWith("json")) {
                normalized = normalized.substring(4).trim();
            }
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ex) {
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            String body = normalized.substring(start, end + 1);
            try {
                return objectMapper.readTree(body);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
