package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.service.mcp.McpServerAvailabilityGate;
import com.linlay.agentplatform.service.mcp.McpServerRegistryService;
import com.linlay.agentplatform.service.mcp.McpStreamableHttpClient;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class McpToolInvoker implements ToolInvoker {

    private final ToolRegistry toolRegistry;
    private final McpProperties properties;
    private final McpServerRegistryService serverRegistryService;
    private final McpServerAvailabilityGate availabilityGate;
    private final McpStreamableHttpClient streamableHttpClient;
    private final ObjectMapper objectMapper;

    public McpToolInvoker(
            ToolRegistry toolRegistry,
            McpProperties properties,
            McpServerRegistryService serverRegistryService,
            McpServerAvailabilityGate availabilityGate,
            McpStreamableHttpClient streamableHttpClient,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.streamableHttpClient = streamableHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context) {
        if (!properties.isEnabled()) {
            return error(toolName, "mcp_disabled", "MCP is disabled");
        }
        Optional<ToolDescriptor> toolOptional = toolRegistry.descriptor(toolName);
        if (toolOptional.isEmpty()) {
            return error(toolName, "mcp_tool_missing", "MCP tool not found");
        }
        ToolDescriptor tool = toolOptional.get();
        if (!"mcp".equalsIgnoreCase(tool.sourceType())) {
            return error(toolName, "mcp_source_mismatch", "Tool is not routed to MCP");
        }
        if (!StringUtils.hasText(tool.sourceKey())) {
            return error(toolName, "mcp_source_key_missing", "MCP server key is missing");
        }
        Optional<McpServerRegistryService.RegisteredServer> serverOptional = serverRegistryService.find(tool.sourceKey());
        if (serverOptional.isEmpty()) {
            return error(toolName, "mcp_server_not_found", "MCP server is not registered: " + tool.sourceKey());
        }
        McpServerRegistryService.RegisteredServer server = serverOptional.get();
        if (availabilityGate.isBlocked(server.serverKey())) {
            return error(
                    toolName,
                    "mcp_server_unavailable",
                    "MCP server is unavailable, waiting for scheduled reconnect: " + server.serverKey()
            );
        }

        try {
            JsonNode result = streamableHttpClient.callTool(
                    server,
                    tool.name(),
                    args == null ? Map.of() : args,
                    buildMeta(tool.name(), context)
            );
            availabilityGate.markSuccess(server.serverKey());
            return normalizeCallResult(toolName, result);
        } catch (Exception ex) {
            availabilityGate.markFailure(server.serverKey());
            String message = resolveErrorMessage(ex);
            if (StringUtils.hasText(message)) {
                return error(toolName, "mcp_server_unavailable", message);
            }
            return error(toolName, "mcp_server_unavailable", "MCP server is unavailable: " + server.serverKey());
        }
    }

    private JsonNode normalizeCallResult(String toolName, JsonNode rpcResult) {
        if (rpcResult == null || rpcResult.isNull()) {
            return error(toolName, "mcp_empty_result", "MCP result is empty");
        }
        if (rpcResult.isObject() && rpcResult.path("isError").asBoolean(false)) {
            String errorMessage = extractMcpErrorMessage(rpcResult);
            return error(toolName, "mcp_tool_error", errorMessage);
        }
        JsonNode structured = rpcResult.path("structuredContent");
        if (structured != null && !structured.isMissingNode() && !structured.isNull()) {
            return structured;
        }
        JsonNode content = rpcResult.path("content");
        if (content.isArray() && !content.isEmpty()) {
            JsonNode first = content.get(0);
            if (first != null && first.isObject() && "text".equalsIgnoreCase(first.path("type").asText())) {
                return objectMapper.getNodeFactory().textNode(first.path("text").asText(""));
            }
            return first == null ? rpcResult : first;
        }
        return rpcResult;
    }

    private String extractMcpErrorMessage(JsonNode rpcResult) {
        if (rpcResult == null || rpcResult.isNull()) {
            return "MCP tool call failed";
        }
        if (rpcResult.has("error") && rpcResult.get("error").isTextual()) {
            return rpcResult.get("error").asText();
        }
        JsonNode content = rpcResult.path("content");
        if (content.isArray() && !content.isEmpty()) {
            JsonNode first = content.get(0);
            if (first != null && first.isObject() && first.has("text")) {
                return first.get("text").asText();
            }
        }
        return "MCP tool call failed";
    }

    private String resolveErrorMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (StringUtils.hasText(cursor.getMessage())) {
                return cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return "unknown error";
    }

    private Map<String, Object> buildMeta(String toolName, ExecutionContext context) {
        if (context == null || context.request() == null) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (StringUtils.hasText(context.request().chatId())) {
            meta.put("chatId", context.request().chatId().trim());
        }
        if (StringUtils.hasText(context.request().requestId())) {
            meta.put("requestId", context.request().requestId().trim());
        }
        if (StringUtils.hasText(context.request().runId())) {
            meta.put("runId", context.request().runId().trim());
        }
        if (StringUtils.hasText(toolName)) {
            meta.put("toolName", toolName.trim());
        }
        return meta;
    }

    private ObjectNode error(String toolName, String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("tool", toolName);
        error.put("ok", false);
        error.put("code", code);
        error.put("error", StringUtils.hasText(message) ? message : "unknown error");
        return error;
    }
}
