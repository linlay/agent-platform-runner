package com.linlay.agentplatform.agent.runtime.sandbox;

import java.util.Locale;

public enum SandboxLevel {

    RUN,
    AGENT,
    GLOBAL;

    public static SandboxLevel parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RUN" -> RUN;
            case "AGENT" -> AGENT;
            case "GLOBAL" -> GLOBAL;
            default -> null;
        };
    }
}
