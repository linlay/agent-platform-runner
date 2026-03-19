package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolAdapters;
import com.linlay.agentplatform.tool.ToolDescriptor;

/**
 * Extracted configured tool placeholder/adapters from DefinitionDrivenAgent.
 */
final class ConfiguredToolAdapters {

    private ConfiguredToolAdapters() {
    }

    static BaseTool declaredPlaceholder(String name) {
        return ToolAdapters.declaredPlaceholder(name);
    }

    static BaseTool resolvedConfiguredTool(String configuredName, ToolDescriptor descriptor) {
        return ToolAdapters.descriptorBacked(configuredName, descriptor);
    }
}
