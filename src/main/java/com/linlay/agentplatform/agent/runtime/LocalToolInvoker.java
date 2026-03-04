package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LocalToolInvoker implements ToolInvoker {

    private final ToolRegistry toolRegistry;

    public LocalToolInvoker(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context) {
        return toolRegistry.invoke(toolName, args);
    }
}
