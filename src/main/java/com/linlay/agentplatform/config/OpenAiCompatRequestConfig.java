package com.linlay.agentplatform.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record OpenAiCompatRequestConfig(Map<String, Object> whenReasoningEnabled) {

    public OpenAiCompatRequestConfig {
        whenReasoningEnabled = immutableNullableMap(whenReasoningEnabled);
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
