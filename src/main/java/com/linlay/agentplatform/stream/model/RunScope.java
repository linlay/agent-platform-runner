package com.linlay.agentplatform.stream.model;

import org.springframework.util.StringUtils;

public record RunScope(
        String chatId,
        String runId,
        RunActor actor
) {

    public RunScope {
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (!StringUtils.hasText(runId)) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        actor = actor == null ? RunActor.primary(null) : actor;
    }

    public static RunScope primary(String chatId, String runId, String actorName) {
        return new RunScope(chatId, runId, RunActor.primary(actorName));
    }

    public RunScope child(RunActor childActor) {
        return new RunScope(chatId, runId, childActor);
    }
}
