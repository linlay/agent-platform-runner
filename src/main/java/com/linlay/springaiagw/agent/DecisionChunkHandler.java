package com.linlay.springaiagw.agent;

import com.aiagent.agw.sdk.model.LlmDelta;
import com.aiagent.agw.sdk.model.ToolCallDelta;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.model.agw.AgentDelta;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 处理 LLM 流式 delta 的首字符检测与分发逻辑。
 * 核心方法 {@link #handleDecisionChunk} 实现零缓冲真流式推送。
 */
class DecisionChunkHandler {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final String agentId;
    private final Function<String, String> toolTypeResolver;

    DecisionChunkHandler(ObjectMapper objectMapper, String agentId) {
        this(objectMapper, agentId, name -> "function");
    }

    DecisionChunkHandler(ObjectMapper objectMapper, String agentId, Function<String, String> toolTypeResolver) {
        this.objectMapper = objectMapper;
        this.agentId = agentId;
        this.toolTypeResolver = toolTypeResolver == null ? name -> "function" : toolTypeResolver;
    }

    void handleDecisionChunk(
            LlmDelta chunk,
            reactor.core.publisher.SynchronousSink<AgentDelta> sink,
            StringBuilder rawBuffer,
            StringBuilder emittedThinking,
            Map<String, NativeToolCall> nativeToolCalls,
            AtomicInteger nativeToolSeq,
            boolean[] formatDecided,
            boolean[] plainTextDetected,
            List<String> earlyBuffer
    ) {
        if (chunk == null) {
            return;
        }
        if (StringUtils.hasText(chunk.content())) {
            rawBuffer.append(chunk.content());

            if (!formatDecided[0]) {
                earlyBuffer.add(chunk.content());
                String trimmed = rawBuffer.toString().trim();
                if (!trimmed.isEmpty()) {
                    formatDecided[0] = true;
                    if (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '`') {
                        plainTextDetected[0] = true;
                        for (String buffered : earlyBuffer) {
                            sink.next(AgentDelta.content(buffered));
                        }
                        earlyBuffer.clear();
                    }
                }
            } else if (plainTextDetected[0]) {
                sink.next(AgentDelta.content(chunk.content()));
            }
        }
        List<ToolCallDelta> streamedToolCalls = captureNativeToolCalls(
                chunk.toolCalls(),
                nativeToolCalls,
                nativeToolSeq
        );
        if (!streamedToolCalls.isEmpty()) {
            sink.next(AgentDelta.toolCalls(streamedToolCalls));
        }
        // Thinking extraction only applies to JSON decision format
        if (!plainTextDetected[0]) {
            String thinkingDelta = extractNewThinkingDelta(rawBuffer, emittedThinking);
            if (!thinkingDelta.isEmpty()) {
                sink.next(AgentDelta.thinking(thinkingDelta));
            }
        }
    }

    String extractNewThinkingDelta(StringBuilder rawPlanBuffer, StringBuilder emittedThinking) {
        String current = extractThinkingFieldValue(rawPlanBuffer.toString());
        if (current.isEmpty() || current.length() <= emittedThinking.length()) {
            return "";
        }
        String delta = current.substring(emittedThinking.length());
        emittedThinking.append(delta);
        return delta;
    }

    String extractThinkingFieldValue(String rawPlan) {
        if (rawPlan == null || rawPlan.isBlank()) {
            return "";
        }

        int keyStart = rawPlan.indexOf("\"thinking\"");
        if (keyStart < 0) {
            return "";
        }

        int colon = rawPlan.indexOf(':', keyStart + 10);
        if (colon < 0) {
            return "";
        }

        int valueStart = skipWhitespace(rawPlan, colon + 1);
        if (valueStart >= rawPlan.length() || rawPlan.charAt(valueStart) != '"') {
            return "";
        }

        StringBuilder value = new StringBuilder();
        int i = valueStart + 1;
        while (i < rawPlan.length()) {
            char ch = rawPlan.charAt(i);
            if (ch == '"') {
                return value.toString();
            }
            if (ch != '\\') {
                value.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= rawPlan.length()) {
                return value.toString();
            }

            char escaped = rawPlan.charAt(i + 1);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 5 >= rawPlan.length()) {
                        return value.toString();
                    }
                    String hex = rawPlan.substring(i + 2, i + 6);
                    if (!isHex(hex)) {
                        value.append("\\u").append(hex);
                    } else {
                        value.append((char) Integer.parseInt(hex, 16));
                    }
                    i += 4;
                }
                default -> value.append(escaped);
            }
            i += 2;
        }

        return value.toString();
    }

    List<ToolCallDelta> captureNativeToolCalls(
            List<ToolCallDelta> chunkToolCalls,
            Map<String, NativeToolCall> collector,
            AtomicInteger seq
    ) {
        if (chunkToolCalls == null || chunkToolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCallDelta> streamed = new ArrayList<>();
        for (ToolCallDelta toolCall : chunkToolCalls) {
            if (toolCall == null) {
                continue;
            }
            String toolId = normalize(toolCall.id(), "");
            if (toolId.isBlank()) {
                toolId = latestCollectorToolId(collector);
            }
            if (toolId.isBlank()) {
                toolId = "call_native_" + seq.incrementAndGet();
            }
            NativeToolCall nativeCall = collector.computeIfAbsent(toolId, NativeToolCall::new);
            if (StringUtils.hasText(toolCall.name())) {
                nativeCall.toolName = toolCall.name();
            }
            if (StringUtils.hasText(toolCall.arguments())) {
                nativeCall.arguments.append(toolCall.arguments());
            }

            String emittedArgs = toolCall.arguments();
            if (!StringUtils.hasText(emittedArgs)) {
                continue;
            }
            String normalizedType = normalize(toolCall.type(), "function");
            String emittedName = StringUtils.hasText(toolCall.name())
                    ? toolCall.name()
                    : nativeCall.toolName;
            String resolvedType = normalize(
                    toolTypeResolver.apply(normalizeToolName(emittedName)),
                    normalizedType
            );
            streamed.add(new ToolCallDelta(
                    toolId,
                    resolvedType,
                    emittedName,
                    emittedArgs
            ));
        }
        return streamed;
    }

    List<PlannedToolCall> toPlannedToolCalls(Map<String, NativeToolCall> collector) {
        if (collector == null || collector.isEmpty()) {
            return List.of();
        }
        List<PlannedToolCall> calls = new ArrayList<>();
        for (NativeToolCall nativeCall : collector.values()) {
            String toolName = normalizeToolName(nativeCall.toolName);
            if (toolName.isBlank()) {
                continue;
            }
            Map<String, Object> arguments = parseToolArguments(nativeCall.arguments.toString());
            calls.add(new PlannedToolCall(toolName, arguments, nativeCall.toolId));
        }
        return calls;
    }

    private Map<String, Object> parseToolArguments(String rawArguments) {
        if (!StringUtils.hasText(rawArguments)) {
            return new LinkedHashMap<>();
        }
        String normalized = rawArguments.trim();
        try {
            JsonNode node = objectMapper.readTree(normalized);
            if (!node.isObject()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
            return converted == null ? new LinkedHashMap<>() : new LinkedHashMap<>(converted);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String latestCollectorToolId(Map<String, NativeToolCall> collector) {
        if (collector == null || collector.isEmpty()) {
            return "";
        }
        String latest = "";
        for (String id : collector.keySet()) {
            latest = id;
        }
        return latest;
    }

    private int skipWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lower = ch >= 'a' && ch <= 'f';
            boolean upper = ch >= 'A' && ch <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    static String normalizeToolName(String raw) {
        return normalize(raw, "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
