package com.linlay.agentplatform.engine.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolInvokerRouter implements ToolInvoker {

    private final ToolRegistry toolRegistry;
    private final LocalToolInvoker localToolInvoker;
    private final McpToolInvoker mcpToolInvoker;

    public ToolInvokerRouter(
            ToolRegistry toolRegistry,
            LocalToolInvoker localToolInvoker,
            ObjectProvider<McpToolInvoker> mcpToolInvokerProvider
    ) {
        this.toolRegistry = toolRegistry;
        this.localToolInvoker = localToolInvoker;
        this.mcpToolInvoker = mcpToolInvokerProvider.getIfAvailable();
    }

    @Override
    public JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context) {
        ToolDescriptor descriptor = context == null ? null : context.toolDescriptor(toolName);
        if (descriptor == null) {
            descriptor = toolRegistry.descriptor(toolName).orElse(null);
        }
        if (descriptor != null && "mcp".equalsIgnoreCase(descriptor.sourceType())) {
            if (mcpToolInvoker == null) {
                throw new IllegalStateException("MCP tool invoker is not available");
            }
            return mcpToolInvoker.invoke(toolName, args, context);
        }
        return localToolInvoker.invoke(toolName, args, context);
    }
}
