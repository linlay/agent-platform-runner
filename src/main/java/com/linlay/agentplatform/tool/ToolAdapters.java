package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public final class ToolAdapters {

    private static final Map<String, Object> DEFAULT_PLACEHOLDER_PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", true
    );

    private ToolAdapters() {
    }

    public static BaseTool declaredPlaceholder(String name) {
        return new DeclaredToolPlaceholder(name);
    }

    public static BaseTool descriptorBacked(String configuredName, ToolDescriptor descriptor) {
        return new DescriptorBackedTool(configuredName, descriptor);
    }

    private static final class DeclaredToolPlaceholder implements BaseTool {
        private final String name;

        private DeclaredToolPlaceholder(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Configured tool placeholder. Runtime will validate registration when invoked.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return DEFAULT_PLACEHOLDER_PARAMETERS;
        }

        @Override
        public JsonNode invoke(Map<String, Object> args) {
            throw new IllegalStateException("Declared tool placeholder cannot be invoked directly: " + name);
        }
    }

    private static final class DescriptorBackedTool implements BaseTool, ToolMetadataAware {
        private final String configuredName;
        private final ToolDescriptor descriptor;

        private DescriptorBackedTool(String configuredName, ToolDescriptor descriptor) {
            this.configuredName = configuredName;
            this.descriptor = descriptor;
        }

        @Override
        public String name() {
            return configuredName;
        }

        @Override
        public String description() {
            return descriptor.description();
        }

        @Override
        public String afterCallHint() {
            return descriptor.afterCallHint();
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return descriptor.parameters();
        }

        @Override
        public JsonNode invoke(Map<String, Object> args) {
            throw new IllegalStateException("Descriptor-backed tool cannot be invoked directly: " + configuredName);
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }
    }
}
