package com.linlay.agentplatform.tool;

import java.util.Map;

public record ToolDescriptor(
        String name,
        String description,
        String afterCallHint,
        Map<String, Object> parameters,
        Boolean strict,
        ToolKind kind,
        String toolType,
        String toolApi,
        String sourceType,
        String sourceKey,
        String viewportKey,
        String sourceFile
) {
    public ToolDescriptor {
        if (parameters == null || parameters.isEmpty()) {
            parameters = Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "additionalProperties", true
            );
        } else {
            parameters = Map.copyOf(parameters);
        }
        sourceType = sourceType == null || sourceType.isBlank() ? "local" : sourceType.trim().toLowerCase();
        sourceKey = sourceKey == null || sourceKey.isBlank() ? null : sourceKey.trim().toLowerCase();
    }
}
