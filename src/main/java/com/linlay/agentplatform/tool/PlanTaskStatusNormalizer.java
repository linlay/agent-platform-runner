package com.linlay.agentplatform.tool;

import java.util.Locale;

final class PlanTaskStatusNormalizer {

    private PlanTaskStatusNormalizer() {
    }

    static String normalizeStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            return "init";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "init", "completed", "failed", "canceled" -> normalized;
            default -> null;
        };
    }
}
