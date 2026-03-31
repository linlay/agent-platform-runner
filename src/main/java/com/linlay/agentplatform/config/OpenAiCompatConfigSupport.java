package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenAiCompatConfigSupport {

    private static final Set<String> RESERVED_REQUEST_KEYS = Set.of(
            "model",
            "stream",
            "stream_options",
            "messages",
            "max_tokens",
            "enable_thinking",
            "reasoning",
            "tools",
            "tool_choice",
            "parallel_tool_calls",
            "response_format"
    );

    private OpenAiCompatConfigSupport() {
    }

    public static OpenAiCompatConfig parse(JsonNode node, String scope) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        OpenAiCompatRequestConfig request = parseRequest(node.path("request"), scope);
        OpenAiCompatResponseConfig response = parseResponse(node.path("response"), scope);
        if (request == null && response == null) {
            return null;
        }
        return new OpenAiCompatConfig(request, response);
    }

    public static OpenAiCompatConfig merge(OpenAiCompatConfig base, OpenAiCompatConfig override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        return new OpenAiCompatConfig(
                mergeRequest(base.request(), override.request()),
                mergeResponse(base.response(), override.response())
        );
    }

    private static OpenAiCompatRequestConfig parseRequest(JsonNode node, String scope) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        Map<String, Object> whenReasoningEnabled = null;
        JsonNode whenReasoningEnabledNode = node.path("whenReasoningEnabled");
        if (!whenReasoningEnabledNode.isMissingNode() && !whenReasoningEnabledNode.isNull()) {
            if (!whenReasoningEnabledNode.isObject()) {
                throw new IllegalStateException("Invalid OpenAI compat request.whenReasoningEnabled in " + scope + ": must be an object");
            }
            whenReasoningEnabled = asNullableMap(whenReasoningEnabledNode);
            validateReservedRequestKeys(whenReasoningEnabled, scope);
        }
        if (whenReasoningEnabled == null) {
            return null;
        }
        return new OpenAiCompatRequestConfig(whenReasoningEnabled);
    }

    private static OpenAiCompatResponseConfig parseResponse(JsonNode node, String scope) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        List<ReasoningFormat> reasoningFormats = null;
        JsonNode reasoningFormatsNode = node.path("reasoningFormats");
        if (!reasoningFormatsNode.isMissingNode() && !reasoningFormatsNode.isNull()) {
            if (!reasoningFormatsNode.isArray()) {
                throw new IllegalStateException("Invalid OpenAI compat response.reasoningFormats in " + scope + ": must be an array");
            }
            reasoningFormats = new ArrayList<>();
            for (JsonNode item : reasoningFormatsNode) {
                if (!item.isTextual()) {
                    throw new IllegalStateException("Invalid OpenAI compat reasoning format in " + scope + ": must be a string");
                }
                reasoningFormats.add(ReasoningFormat.valueOf(item.asText().trim().toUpperCase(Locale.ROOT)));
            }
        }

        ThinkTagConfig thinkTag = null;
        JsonNode thinkTagNode = node.path("thinkTag");
        if (!thinkTagNode.isMissingNode() && !thinkTagNode.isNull()) {
            if (!thinkTagNode.isObject()) {
                throw new IllegalStateException("Invalid OpenAI compat response.thinkTag in " + scope + ": must be an object");
            }
            thinkTag = new ThinkTagConfig(
                    optionalText(thinkTagNode.get("start")),
                    optionalText(thinkTagNode.get("end")),
                    optionalBoolean(thinkTagNode.get("stripFromContent"))
            );
        }

        if (reasoningFormats == null && thinkTag == null) {
            return null;
        }
        return new OpenAiCompatResponseConfig(reasoningFormats, thinkTag);
    }

    private static void validateReservedRequestKeys(Map<String, Object> fields, String scope) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (String key : fields.keySet()) {
            if (key != null && RESERVED_REQUEST_KEYS.contains(key.trim())) {
                conflicts.add(key.trim());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid OpenAI compat request.whenReasoningEnabled in %s: reserved keys are not allowed: %s"
                            .formatted(scope, conflicts)
            );
        }
    }

    private static OpenAiCompatRequestConfig mergeRequest(OpenAiCompatRequestConfig base, OpenAiCompatRequestConfig override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        Map<String, Object> merged = mergeNullableMaps(base.whenReasoningEnabled(), override.whenReasoningEnabled());
        return merged == null ? null : new OpenAiCompatRequestConfig(merged);
    }

    private static OpenAiCompatResponseConfig mergeResponse(OpenAiCompatResponseConfig base, OpenAiCompatResponseConfig override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        List<ReasoningFormat> reasoningFormats = override.reasoningFormats() != null
                ? override.reasoningFormats()
                : base.reasoningFormats();
        ThinkTagConfig thinkTag = mergeThinkTag(base.thinkTag(), override.thinkTag());
        if (reasoningFormats == null && thinkTag == null) {
            return null;
        }
        return new OpenAiCompatResponseConfig(reasoningFormats, thinkTag);
    }

    private static ThinkTagConfig mergeThinkTag(ThinkTagConfig base, ThinkTagConfig override) {
        if (base == null) {
            return override;
        }
        if (override == null) {
            return base;
        }
        String start = override.start() != null ? override.start() : base.start();
        String end = override.end() != null ? override.end() : base.end();
        Boolean stripFromContent = override.stripFromContent() != null ? override.stripFromContent() : base.stripFromContent();
        if (!StringUtils.hasText(start) && !StringUtils.hasText(end) && stripFromContent == null) {
            return null;
        }
        return new ThinkTagConfig(start, end, stripFromContent);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeNullableMaps(Map<String, Object> base, Map<String, Object> override) {
        if (base == null) {
            return override == null ? null : new LinkedHashMap<>(override);
        }
        if (override == null) {
            return new LinkedHashMap<>(base);
        }
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object overrideValue = entry.getValue();
            if (overrideValue == null) {
                merged.remove(key);
                continue;
            }
            Object baseValue = merged.get(key);
            if (baseValue instanceof Map<?, ?> baseMap && overrideValue instanceof Map<?, ?> overrideMap) {
                merged.put(key, mergeNullableMaps((Map<String, Object>) baseMap, (Map<String, Object>) overrideMap));
                continue;
            }
            merged.put(key, overrideValue);
        }
        return merged;
    }

    public static Map<String, Object> asNullableMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), normalizeValue(entry.getValue())));
        return result;
    }

    private static Object normalizeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            return asNullableMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(normalizeValue(item));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) {
            return node.doubleValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.toString();
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }

    private static Boolean optionalBoolean(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isBoolean() ? node.asBoolean() : Boolean.valueOf(node.asText());
    }
}
