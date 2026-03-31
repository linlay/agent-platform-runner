package com.linlay.agentplatform.stream.adapter.openai;

import com.linlay.agentplatform.config.OpenAiCompatConfig;
import com.linlay.agentplatform.config.OpenAiCompatResponseConfig;
import com.linlay.agentplatform.config.ReasoningFormat;
import com.linlay.agentplatform.config.ThinkTagConfig;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OpenAiSseDeltaParser {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSseDeltaParser.class);

    private final ObjectMapper objectMapper;
    private final List<ReasoningFormat> reasoningFormats;
    private final ThinkTagConfig thinkTagConfig;
    private final boolean thinkTagEnabled;
    private final String thinkTagStart;
    private final String thinkTagEnd;
    private final boolean stripThinkTagFromContent;
    private final StringBuilder thinkBuffer = new StringBuilder();
    private boolean insideThinkTag;

    public OpenAiSseDeltaParser(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public OpenAiSseDeltaParser(ObjectMapper objectMapper, OpenAiCompatConfig compat) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        OpenAiCompatResponseConfig response = compat == null ? null : compat.response();
        this.reasoningFormats = response == null || response.reasoningFormats() == null
                ? List.of(ReasoningFormat.REASONING_CONTENT)
                : response.reasoningFormats();
        this.thinkTagConfig = response == null ? null : response.thinkTag();
        this.thinkTagEnabled = reasoningFormats.contains(ReasoningFormat.THINK_TAG_CONTENT);
        this.thinkTagStart = thinkTagConfig != null && hasText(thinkTagConfig.start()) ? thinkTagConfig.start() : "<think>";
        this.thinkTagEnd = thinkTagConfig != null && hasText(thinkTagConfig.end()) ? thinkTagConfig.end() : "</think>";
        this.stripThinkTagFromContent = thinkTagConfig == null || thinkTagConfig.stripFromContentOrDefault();
    }

    public LlmDelta parseOrNull(String rawChunk) {
        String payload = normalizePayload(rawChunk);
        if (payload == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            Map<String, Object> usage = parseUsage(root.get("usage"));
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                if (usage != null && !usage.isEmpty()) {
                    return new LlmDelta(null, null, null, null, usage);
                }
                return null;
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode deltaNode = firstChoice.path("delta");
            String reasoning = parseReasoning(deltaNode);
            ParsedThinkTagContent parsedContent = parseThinkTagContent(optionalText(deltaNode.get("content")));
            String content = parsedContent.content();
            if (hasText(parsedContent.reasoning())) {
                reasoning = appendText(reasoning, parsedContent.reasoning());
            }
            String finishReason = optionalText(firstChoice.get("finish_reason"));

            List<ToolCallDelta> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = deltaNode.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode toolCallNode : toolCallsNode) {
                    String id = optionalText(toolCallNode.get("id"));
                    Integer index = optionalInt(toolCallNode.get("index"));
                    String type = optionalText(toolCallNode.get("type"));
                    JsonNode functionNode = toolCallNode.path("function");
                    String name = optionalText(functionNode.get("name"));
                    String arguments = optionalText(functionNode.get("arguments"));
                    if (!hasText(id) && index == null && !hasText(name) && !hasText(arguments)) {
                        continue;
                    }
                    toolCalls.add(new ToolCallDelta(id, index, type, name, arguments));
                }
            }

            boolean empty = !hasText(reasoning)
                    && !hasText(content)
                    && toolCalls.isEmpty()
                    && !hasText(finishReason)
                    && (usage == null || usage.isEmpty());
            if (empty) {
                return null;
            }
            return new LlmDelta(
                    reasoning,
                    content,
                    toolCalls.isEmpty() ? null : toolCalls,
                    finishReason,
                    usage
            );
        } catch (Exception ex) {
            log.warn("Failed to parse OpenAI SSE chunk: {}", rawChunk, ex);
            return null;
        }
    }

    private String parseReasoning(JsonNode deltaNode) {
        String reasoning = null;
        if (reasoningFormats.contains(ReasoningFormat.REASONING_CONTENT)) {
            reasoning = appendText(reasoning, optionalText(deltaNode.get("reasoning_content")));
        }
        if (reasoningFormats.contains(ReasoningFormat.REASONING_DETAILS_TEXT)) {
            reasoning = appendText(reasoning, parseReasoningDetails(deltaNode.get("reasoning_details")));
        }
        return reasoning;
    }

    private String parseReasoningDetails(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : node) {
            String text = optionalText(item.get("text"));
            if (hasText(text)) {
                builder.append(text);
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private ParsedThinkTagContent parseThinkTagContent(String rawContent) {
        if (!thinkTagEnabled) {
            return new ParsedThinkTagContent(null, rawContent);
        }
        if (rawContent == null) {
            return new ParsedThinkTagContent(null, null);
        }
        thinkBuffer.append(rawContent);
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();

        while (thinkBuffer.length() > 0) {
            if (insideThinkTag) {
                int endIndex = thinkBuffer.indexOf(thinkTagEnd);
                if (endIndex >= 0) {
                    appendRange(thinkBuffer, 0, endIndex, reasoning, content, true);
                    if (!stripThinkTagFromContent) {
                        content.append(thinkTagEnd);
                    }
                    thinkBuffer.delete(0, endIndex + thinkTagEnd.length());
                    insideThinkTag = false;
                    continue;
                }
                int keep = longestSuffixPrefix(thinkBuffer, thinkTagEnd);
                appendRange(thinkBuffer, 0, thinkBuffer.length() - keep, reasoning, content, true);
                thinkBuffer.delete(0, thinkBuffer.length() - keep);
                break;
            }

            int startIndex = thinkBuffer.indexOf(thinkTagStart);
            if (startIndex >= 0) {
                appendRange(thinkBuffer, 0, startIndex, reasoning, content, false);
                if (!stripThinkTagFromContent) {
                    content.append(thinkTagStart);
                }
                thinkBuffer.delete(0, startIndex + thinkTagStart.length());
                insideThinkTag = true;
                continue;
            }
            int keep = longestSuffixPrefix(thinkBuffer, thinkTagStart);
            appendRange(thinkBuffer, 0, thinkBuffer.length() - keep, reasoning, content, false);
            thinkBuffer.delete(0, thinkBuffer.length() - keep);
            break;
        }

        return new ParsedThinkTagContent(
                reasoning.isEmpty() ? null : reasoning.toString(),
                content.isEmpty() ? null : content.toString()
        );
    }

    private void appendRange(StringBuilder source, int start, int end, StringBuilder reasoning, StringBuilder content, boolean reasoningMode) {
        if (end <= start) {
            return;
        }
        String segment = source.substring(start, end);
        if (reasoningMode) {
            reasoning.append(segment);
            if (!stripThinkTagFromContent) {
                content.append(segment);
            }
            return;
        }
        content.append(segment);
    }

    private int longestSuffixPrefix(CharSequence source, String marker) {
        if (!hasText(marker) || source == null || source.length() == 0) {
            return 0;
        }
        int max = Math.min(source.length(), marker.length() - 1);
        for (int len = max; len > 0; len--) {
            boolean matches = true;
            for (int i = 0; i < len; i++) {
                if (source.charAt(source.length() - len + i) != marker.charAt(i)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return len;
            }
        }
        return 0;
    }

    private String appendText(String base, String addition) {
        if (!hasText(addition)) {
            return base;
        }
        if (!hasText(base)) {
            return addition;
        }
        return base + addition;
    }

    private Map<String, Object> parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull() || usageNode.isMissingNode() || !usageNode.isObject()) {
            return null;
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usageNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            Object normalized = normalizeUsageValue(valueNode);
            if (normalized != null) {
                usage.put(entry.getKey(), normalized);
            }
        });
        return usage.isEmpty() ? null : usage;
    }

    private Object normalizeUsageValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull() || valueNode.isMissingNode()) {
            return null;
        }
        if (valueNode.isInt() || valueNode.isLong()) {
            return valueNode.asLong();
        }
        if (valueNode.isFloat() || valueNode.isDouble() || valueNode.isBigDecimal()) {
            return valueNode.doubleValue();
        }
        if (valueNode.isNumber()) {
            return valueNode.numberValue();
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        if (valueNode.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : valueNode) {
                list.add(normalizeUsageValue(item));
            }
            return list;
        }
        if (valueNode.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            valueNode.fields().forEachRemaining(entry ->
                    map.put(entry.getKey(), normalizeUsageValue(entry.getValue()))
            );
            return map;
        }
        return valueNode.toString();
    }

    private String normalizePayload(String rawChunk) {
        if (!hasText(rawChunk)) {
            return null;
        }
        String payload = rawChunk.trim();
        if (payload.startsWith("data:")) {
            payload = payload.substring(5).trim();
        }
        if (!hasText(payload) || "[DONE]".equals(payload)) {
            return null;
        }
        return payload;
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private Integer optionalInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasText(String text) {
        return StringHelpers.hasText(text);
    }

    private record ParsedThinkTagContent(String reasoning, String content) {
    }
}
