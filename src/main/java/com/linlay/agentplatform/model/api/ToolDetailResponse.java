package com.linlay.agentplatform.model.api;

import java.util.Map;

public record ToolDetailResponse(
        String key,
        String name,
        String label,
        String description,
        String afterCallHint,
        Map<String, Object> parameters,
        Map<String, Object> meta
) {
}
