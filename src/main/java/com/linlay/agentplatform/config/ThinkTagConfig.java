package com.linlay.agentplatform.config;

public record ThinkTagConfig(
        String start,
        String end,
        Boolean stripFromContent
) {

    public ThinkTagConfig {
        start = normalize(start);
        end = normalize(end);
    }

    public boolean stripFromContentOrDefault() {
        return stripFromContent == null || stripFromContent;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
