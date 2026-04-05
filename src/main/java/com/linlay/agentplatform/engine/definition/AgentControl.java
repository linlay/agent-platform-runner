package com.linlay.agentplatform.engine.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentControl(
        String key,
        String type,
        String label,
        String description,
        Object defaultValue,
        Boolean required,
        Boolean multiple,
        Object icon,
        String instruction,
        List<Option> options
) {
    private static final Set<String> ALLOWED_TYPES = Set.of("number", "boolean", "select", "switch");

    public AgentControl {
        key = requireText(key, "controls[].key");
        type = requireType(type);
        label = requireText(label, "controls[].label");
        description = normalizeOptionalText(description);
        instruction = normalizeOptionalText(instruction);
        options = normalizeOptions(type, options);
        validateDefaultValue(type, defaultValue);
        if (!"select".equals(type) && Boolean.TRUE.equals(multiple)) {
            throw new IllegalArgumentException("controls[" + key + "].multiple is only allowed for select");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(
            Object value,
            String label,
            Object icon,
            String type,
            Map<String, Object> meta
    ) {
        public Option {
            if (value == null) {
                throw new IllegalArgumentException("controls[].options[].value is required");
            }
            label = normalizeOptionalText(label);
            type = normalizeOptionalText(type);
            if (meta != null && !meta.isEmpty()) {
                meta = Map.copyOf(new LinkedHashMap<>(meta));
            } else {
                meta = null;
            }
        }
    }

    private static String requireType(String raw) {
        String normalized = requireText(raw, "controls[].type").toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("controls[].type must be one of " + ALLOWED_TYPES);
        }
        return normalized;
    }

    private static String requireText(String raw, String fieldName) {
        String normalized = normalizeOptionalText(raw);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<Option> normalizeOptions(String type, List<Option> options) {
        if ("select".equals(type)) {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("controls[].options is required for select");
            }
            return List.copyOf(options);
        }
        if (options != null && !options.isEmpty()) {
            throw new IllegalArgumentException("controls[].options is only allowed for select");
        }
        return List.of();
    }

    private static void validateDefaultValue(String type, Object defaultValue) {
        if (defaultValue == null) {
            return;
        }
        if (("boolean".equals(type) || "switch".equals(type)) && !(defaultValue instanceof Boolean)) {
            throw new IllegalArgumentException("controls[].defaultValue must be boolean when type=" + type);
        }
        if ("number".equals(type) && !(defaultValue instanceof Number)) {
            throw new IllegalArgumentException("controls[].defaultValue must be number when type=number");
        }
    }
}
