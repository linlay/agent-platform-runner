package com.linlay.agentplatform.schedule;

import java.util.Map;

public record ScheduledQueryDescriptor(
        String id,
        String name,
        String description,
        boolean enabled,
        String cron,
        String agentKey,
        String teamId,
        Environment environment,
        Query query,
        String sourceFile
) {
    public ScheduledQueryDescriptor {
        environment = environment == null ? new Environment(null) : environment;
        query = query == null ? new Query(null, null, Map.of()) : query;
    }

    public record Environment(
            String zoneId
    ) {
    }

    public record Query(
            String message,
            String chatId,
            Map<String, Object> params
    ) {
        public Query {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
