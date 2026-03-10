package com.linlay.agentplatform.tool;

import java.util.Map;

public final class ToolMetadataValidator {

    private ToolMetadataValidator() {
    }

    public static String validate(
            String type,
            String name,
            String description,
            String label,
            Map<String, Object> inputSchema,
            Boolean toolAction,
            String toolType,
            String viewportKey
    ) {
        if (!hasText(type)) {
            return "type is required";
        }
        if (!"function".equalsIgnoreCase(type.trim())) {
            return "type must be function";
        }
        if (!hasText(name)) {
            return "name is required";
        }
        if (!hasText(description)) {
            return "description is required";
        }
        if (label != null && label.isBlank()) {
            return "label must not be blank";
        }
        if (inputSchema == null || inputSchema.isEmpty()) {
            return "inputSchema is required";
        }
        boolean hasToolType = hasText(toolType);
        boolean hasViewportKey = hasText(viewportKey);
        if (hasToolType != hasViewportKey) {
            return "toolType and viewportKey must appear together";
        }
        if (Boolean.TRUE.equals(toolAction) && (hasToolType || hasViewportKey)) {
            return "toolAction=true cannot be used with toolType or viewportKey";
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
