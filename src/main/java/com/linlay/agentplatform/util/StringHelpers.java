package com.linlay.agentplatform.util;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

public final class StringHelpers {

    private StringHelpers() {
    }

    public static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static String normalizeKey(String value) {
        return trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    public static String trimOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static String nullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    public static boolean isValidChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return false;
        }
        try {
            UUID.fromString(chatId.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
