package com.linlay.agentplatform.schedule;

import java.util.Map;

public record ScheduledQueryDescriptor(
        String id,
        String name,
        boolean enabled,
        String cron,
        String zoneId,
        String agentKey,
        String teamId,
        String query,
        Map<String, Object> params,
        String sourceFile
) {
    public ScheduledQueryDescriptor {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
