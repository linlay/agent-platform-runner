package com.linlay.agentplatform.model.api;

import java.util.List;
import java.util.Map;

public record ToolListResponse(
        List<ToolSummary> tools
) {
    public record ToolSummary(
            String key,
            String name,
            String description,
            Map<String, Object> meta
    ) {
    }
}
