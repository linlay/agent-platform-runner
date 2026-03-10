package com.linlay.agentplatform.tool;

import java.util.Map;

public record ToolDescriptor(
        String name,
        String description,
        String afterCallHint,
        Map<String, Object> parameters,
        Boolean strict,
        Boolean clientVisible,
        Boolean toolAction,
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
        clientVisible = clientVisible == null ? Boolean.TRUE : clientVisible;
        toolAction = toolAction == null ? Boolean.FALSE : toolAction;
        toolType = toolType == null || toolType.isBlank() ? null : toolType.trim();
        toolApi = toolApi == null || toolApi.isBlank() ? null : toolApi.trim();
        viewportKey = viewportKey == null || viewportKey.isBlank() ? null : viewportKey.trim();
        sourceType = sourceType == null || sourceType.isBlank() ? "local" : sourceType.trim().toLowerCase();
        sourceKey = sourceKey == null || sourceKey.isBlank() ? null : sourceKey.trim().toLowerCase();
    }

    public boolean isAction() {
        return Boolean.TRUE.equals(toolAction);
    }

    public boolean hasViewport() {
        return !isAction() && hasText(toolType) && hasText(viewportKey);
    }

    public boolean requiresFrontendSubmit() {
        return hasViewport() && !"mcp".equalsIgnoreCase(sourceType);
    }

    public boolean isFrontend() {
        return hasViewport();
    }

    public ToolKind kind() {
        if (isAction()) {
            return ToolKind.ACTION;
        }
        if (isFrontend()) {
            return ToolKind.FRONTEND;
        }
        return ToolKind.BACKEND;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
