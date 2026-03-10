package com.linlay.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.tool.ToolMetadataValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class McpStreamableHttpClient {

    private static final Logger log = LoggerFactory.getLogger(McpStreamableHttpClient.class);

    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public McpStreamableHttpClient(
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }

    public void initialize(McpServerRegistryService.RegisteredServer server, String protocolVersion) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", StringUtils.hasText(protocolVersion) ? protocolVersion : "2025-06");
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "agent-platform-runner");
        clientInfo.put("version", "0.0.1");
        params.set("clientInfo", clientInfo);
        RpcResponse response = callRpc(server, "initialize", params);
        if (response.error() != null && !response.error().isNull()) {
            throw new IllegalStateException("MCP initialize failed: " + response.error());
        }
    }

    public List<McpToolDefinition> listTools(McpServerRegistryService.RegisteredServer server) {
        RpcResponse response = callRpc(server, "tools/list", objectMapper.createObjectNode());
        if (response.error() != null && !response.error().isNull()) {
            throw new IllegalStateException("MCP tools/list failed: " + response.error());
        }
        JsonNode tools = response.result() == null ? null : response.result().path("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<McpToolDefinition> definitions = new ArrayList<>();
        for (JsonNode node : tools) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String name = text(node.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String label = text(node.get("label"));
            String description = text(node.get("description"));
            String afterCallHint = text(node.get("afterCallHint"));
            JsonNode schema = node.get("inputSchema");
            boolean toolAction = node.path("toolAction").asBoolean(false);
            String toolType = text(node.get("toolType"));
            String viewportKey = text(node.get("viewportKey"));
            Map<String, Object> inputSchema = toInputSchema(schema);
            String validationError = ToolMetadataValidator.validate(
                    "function",
                    name,
                    description,
                    label,
                    inputSchema,
                    toolAction,
                    toolType,
                    viewportKey
            );
            if (validationError != null) {
                log.warn("Skip invalid MCP tool metadata name={}: {}", name, validationError);
                continue;
            }
            JsonNode aliasesNode = node.get("aliases");
            List<String> aliases = new ArrayList<>();
            if (aliasesNode != null && aliasesNode.isArray()) {
                for (JsonNode aliasNode : aliasesNode) {
                    String alias = text(aliasNode);
                    if (StringUtils.hasText(alias)) {
                        aliases.add(alias.trim().toLowerCase());
                    }
                }
            }
            definitions.add(new McpToolDefinition(
                    name.trim(),
                    label,
                    description,
                    afterCallHint,
                    inputSchema,
                    toolAction,
                    toolType,
                    viewportKey,
                    List.copyOf(aliases)
            ));
        }
        return List.copyOf(definitions);
    }

    public JsonNode callTool(
            McpServerRegistryService.RegisteredServer server,
            String toolName,
            Map<String, Object> args
    ) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args == null ? Map.of() : args));
        RpcResponse response = callRpc(server, "tools/call", params);
        if (response.error() != null && !response.error().isNull()) {
            throw new IllegalStateException("MCP tools/call failed: " + response.error());
        }
        return response.result();
    }

    public RemoteViewportResponse fetchViewport(
            McpServerRegistryService.RegisteredServer server,
            String viewportKey
    ) {
        HttpPayload payload = buildClient(server).get()
                .uri(resolveViewportUri(server, viewportKey))
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyHeaders(headers, server.headers()))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new HttpPayload(response.statusCode(), body)))
                .block(Duration.ofMillis(Math.max(1, server.readTimeoutMs())));
        if (payload == null) {
            throw new IllegalStateException("Viewport request returned empty payload");
        }
        ApiResponse<Object> apiResponse = parseApiResponse(payload.body());
        return new RemoteViewportResponse(payload.statusCode().value(), apiResponse);
    }

    private RpcResponse callRpc(McpServerRegistryService.RegisteredServer server, String method, ObjectNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);

        int attempts = Math.max(0, server.retry());
        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            try {
                String body = buildClient(server).post()
                        .uri(server.endpointUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .headers(headers -> applyHeaders(headers, server.headers()))
                        .bodyValue(request)
                        .exchangeToMono(response -> response.bodyToFlux(String.class)
                                .collect(StringBuilder::new, StringBuilder::append)
                                .map(StringBuilder::toString)
                                .defaultIfEmpty(""))
                        .block(Duration.ofMillis(Math.max(1, server.readTimeoutMs())));
                JsonNode payload = parseResponsePayload(body);
                if (payload == null || payload.isNull()) {
                    throw new IllegalStateException("MCP call returned empty payload");
                }
                JsonNode result = payload.get("result");
                JsonNode error = payload.get("error");
                return new RpcResponse(result, error);
            } catch (RuntimeException ex) {
                lastError = ex;
                String reason = summarizeException(ex);
                log.warn("MCP call failed serverKey={}, method={}, attempt={}/{}: {}",
                        server.serverKey(),
                        method,
                        attempt,
                        attempts,
                        reason);
                if (log.isDebugEnabled()) {
                    log.debug("MCP call stack serverKey={}, method={}, attempt={}/{}",
                            server.serverKey(),
                            method,
                            attempt,
                            attempts,
                            ex);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("MCP call failed: " + method) : lastError;
    }

    private WebClient buildClient(McpServerRegistryService.RegisteredServer server) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(1, server.connectTimeoutMs()))
                .responseTimeout(Duration.ofMillis(Math.max(1, server.readTimeoutMs())));
        return webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private void applyHeaders(HttpHeaders headers, Map<String, String> configuredHeaders) {
        if (configuredHeaders == null || configuredHeaders.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            headers.set(entry.getKey(), entry.getValue());
        }
    }

    private JsonNode parseResponsePayload(String body) {
        String normalized = body == null ? "" : body.trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ignored) {
            // fall through to SSE parsing
        }

        JsonNode lastPayload = null;
        StringBuilder block = new StringBuilder();
        for (String line : normalized.split("\\r?\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                JsonNode parsed = parseSseBlock(block.toString());
                if (parsed != null) {
                    lastPayload = parsed;
                }
                block.setLength(0);
                continue;
            }
            if (trimmed.startsWith("data:")) {
                if (!block.isEmpty()) {
                    block.append('\n');
                }
                block.append(trimmed.substring(5).trim());
            }
        }
        JsonNode parsed = parseSseBlock(block.toString());
        if (parsed != null) {
            lastPayload = parsed;
        }
        if (lastPayload != null) {
            return lastPayload;
        }

        // Some decoders may already strip "data:" and emit plain JSON lines.
        for (String line : normalized.split("\\r?\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            JsonNode lineJson = parseSseBlock(trimmed);
            if (lineJson != null) {
                lastPayload = lineJson;
            }
        }
        return lastPayload;
    }

    private JsonNode parseSseBlock(String block) {
        if (!StringUtils.hasText(block)) {
            return null;
        }
        String payload = block.trim();
        if ("[DONE]".equalsIgnoreCase(payload)) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return null;
        }
    }

    private ApiResponse<Object> parseApiResponse(String body) {
        if (!StringUtils.hasText(body)) {
            return ApiResponse.failure(502, "Viewport response body is empty", Map.of());
        }
        try {
            return objectMapper.readValue(body, new TypeReference<ApiResponse<Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse viewport response: " + ex.getMessage(), ex);
        }
    }

    private String resolveViewportUri(McpServerRegistryService.RegisteredServer server, String viewportKey) {
        String baseUrl = server.baseUrl();
        String path = "/api/ap/viewport?viewportKey=" + viewportKey;
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText(null);
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    private Map<String, Object> toInputSchema(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String summarizeException(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (!StringUtils.hasText(message)) {
            return type;
        }
        return type + ": " + message;
    }

    public record McpToolDefinition(
            String name,
            String label,
            String description,
            String afterCallHint,
            Map<String, Object> inputSchema,
            boolean toolAction,
            String toolType,
            String viewportKey,
            List<String> aliases
    ) {
    }

    public record RemoteViewportResponse(
            int statusCode,
            ApiResponse<Object> payload
    ) {
    }

    private record RpcResponse(
            JsonNode result,
            JsonNode error
    ) {
    }

    private record HttpPayload(
            HttpStatusCode statusCode,
            String body
    ) {
    }
}
