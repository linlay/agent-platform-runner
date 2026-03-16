package com.linlay.agentplatform.stream.model;

import org.springframework.util.StringUtils;

public record RunActor(
        String actorId,
        String actorType,
        String actorName
) {

    public RunActor {
        actorId = normalize(actorId);
        actorType = normalize(actorType);
        actorName = normalize(actorName);
    }

    public static RunActor primary(String actorName) {
        return new RunActor("primary", "primary", actorName);
    }

    public static RunActor commander(String actorId, String actorName) {
        return new RunActor(actorId, "commander", actorName);
    }

    public static RunActor subagent(String actorId, String actorName) {
        return new RunActor(actorId, "subagent", actorName);
    }

    public boolean isPresent() {
        return StringUtils.hasText(actorId) || StringUtils.hasText(actorType) || StringUtils.hasText(actorName);
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
