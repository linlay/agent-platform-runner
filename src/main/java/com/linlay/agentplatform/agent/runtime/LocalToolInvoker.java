package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ContextAwareTool;
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
        BaseTool tool = context == null ? null : context.localNativeTool(toolName);
        if (tool == null) {
            tool = toolRegistry.nativeTool(toolName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));
        }
        if (tool instanceof ContextAwareTool contextAwareTool) {
            return contextAwareTool.invoke(args, context);
        }
        return tool.invoke(args);
    }
}
