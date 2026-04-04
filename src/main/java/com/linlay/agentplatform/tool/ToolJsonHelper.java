package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class ToolJsonHelper {

    private ToolJsonHelper() {
    }

    public static String requireText(JsonNode root, String fieldName) {
        return requireText(root, fieldName, fieldName);
    }

    public static String requireText(JsonNode root, String fieldName, String displayName) {
        String value = readText(root, fieldName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing argument: " + displayName);
        }
        return value;
    }

    public static String readText(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static Integer readInteger(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.intValue();
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an integer");
            }
        }
        throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an integer");
    }

    public static List<String> readStringList(JsonNode node) {
        return readStringList(node, "tags");
    }

    public static List<String> readStringList(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull() || !item.isValueNode()) {
                throw new IllegalArgumentException("Invalid argument: " + fieldName + " must be an array of strings");
            }
            String value = item.asText();
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }
}
