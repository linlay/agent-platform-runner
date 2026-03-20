package com.linlay.agentplatform.agent;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RuntimeContextTags {

    public static final String SYSTEM = "system";
    public static final String CONTEXT = "context";
    public static final String OWNER = "owner";
    public static final String AUTH = "auth";

    private static final Set<String> SUPPORTED = Set.of(
            SYSTEM,
            CONTEXT,
            OWNER,
            AUTH
    );

    private RuntimeContextTags() {
    }

    public static boolean isSupported(String rawTag) {
        return SUPPORTED.contains(normalize(rawTag));
    }

    public static List<String> normalize(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawTag : rawTags) {
            String tag = normalize(rawTag);
            if (SUPPORTED.contains(tag)) {
                normalized.add(tag);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static String normalize(String rawTag) {
        if (!StringUtils.hasText(rawTag)) {
            return "";
        }
        return rawTag.trim().toLowerCase(Locale.ROOT);
    }
}
