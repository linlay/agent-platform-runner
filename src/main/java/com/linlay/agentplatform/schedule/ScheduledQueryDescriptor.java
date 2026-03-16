package com.linlay.agentplatform.schedule;

import com.linlay.agentplatform.model.api.QueryRequest;

import java.util.List;
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
        query = query == null ? new Query(null, null, null, null, List.of(), Map.of(), null, null) : query;
    }

    public record Environment(
            String zoneId
    ) {
    }

    public record Query(
            String requestId,
            String chatId,
            String role,
            String message,
            List<QueryRequest.Reference> references,
            Map<String, Object> params,
            QueryRequest.Scene scene,
            Boolean hidden
    ) {
        public Query {
            references = references == null ? List.of() : List.copyOf(references);
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
