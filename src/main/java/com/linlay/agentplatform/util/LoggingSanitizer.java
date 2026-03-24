package com.linlay.agentplatform.util;

import com.linlay.agentplatform.service.llm.LlmLogSanitizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class LoggingSanitizer {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization",
            "token",
            "api-key",
            "apikey",
            "api_key",
            "secret",
            "password",
            "access-token",
            "access_token",
            "refresh-token",
            "refresh_token",
            "x-api-key",
            "bearer",
            "t"
    );

    private LoggingSanitizer() {
    }

    public static String sanitizeText(String text) {
        return LlmLogSanitizer.maskText(text, true);
    }

    public static String maskIfSensitiveKey(String key, String value) {
        return isSensitiveKey(key) ? "***" : sanitizeText(value);
    }

    public static Map<String, Object> sanitizeMap(Map<String, ?> raw) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return safe;
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                safe.put(key, "***");
                continue;
            }
            safe.put(key, sanitizeValue(value));
        }
        return safe;
    }

    private static Object sanitizeValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text) {
            return sanitizeText(text);
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "null" : String.valueOf(entry.getKey());
                if (isSensitiveKey(key)) {
                    normalized.put(key, "***");
                } else {
                    normalized.put(key, sanitizeValue(entry.getValue()));
                }
            }
            return normalized;
        }
        if (raw instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        return raw;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return SENSITIVE_KEYS.contains(normalized);
    }
}
