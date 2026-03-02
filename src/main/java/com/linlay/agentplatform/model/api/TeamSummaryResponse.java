package com.linlay.agentplatform.model.api;

import java.util.List;
import java.util.Map;

public record TeamSummaryResponse(
        String teamId,
        String name,
        Object icon,
        List<String> agentKeys,
        Map<String, Object> meta
) {
}
