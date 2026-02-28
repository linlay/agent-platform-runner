package com.linlay.agentplatform.model.api;

import java.util.Map;

public record ToolDetailResponse(
        ToolDetail tool
) {
    public record ToolDetail(
            String key,
            String name,
            String description,
            String afterCallHint,
            Map<String, Object> parameters,
            Map<String, Object> meta
    ) {
    }
}
