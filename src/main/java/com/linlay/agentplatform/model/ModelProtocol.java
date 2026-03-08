package com.linlay.agentplatform.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

public enum ModelProtocol {
    OPENAI,
    ANTHROPIC;

    @JsonCreator
    public static ModelProtocol fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI;
        }
        String normalized = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OPENAI" -> OPENAI;
            case "ANTHROPIC" -> ANTHROPIC;
            default -> throw new IllegalArgumentException("Unsupported model protocol: " + raw);
        };
    }
}
