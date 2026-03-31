package com.linlay.agentplatform.service.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import com.linlay.agentplatform.tool.ToolMetadataValidator;
import com.linlay.agentplatform.service.viewport.ViewportServerRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        initialize(toEndpoint(server), protocolVersion);
    }

    public void initialize(ViewportServerRegistryService.RegisteredServer server, String protocolVersion) {
        initialize(toEndpoint(server), protocolVersion);
    }

    private void initialize(ServerEndpoint server, String protocolVersion) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", StringUtils.hasText(protocolVersion) ? protocolVersion : "2025-06");
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "agent-platform-runner");
        clientInfo.put("version", "0.0.1");
        params.set("clientInfo", clientInfo);
        RpcResponse response = callRpc(server, "initialize", params);
        if (response.error() != null) {
            throw new RpcErrorException("MCP initialize failed", response.error());
        }
    }

    public List<McpToolDefinition> listTools(McpServerRegistryService.RegisteredServer server) {
        RpcResponse response = callRpc(toEndpoint(server), "tools/list", objectMapper.createObjectNode());
        if (response.error() != null) {
            throw new RpcErrorException("MCP tools/list failed", response.error());
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
            Map<String, Object> args,
            Map<String, Object> meta
    ) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(args == null ? Map.of() : args));
        if (meta != null && !meta.isEmpty()) {
            params.set("_meta", objectMapper.valueToTree(meta));
        }
        RpcResponse response = callRpc(toEndpoint(server), "tools/call", params);
        if (response.error() != null) {
            throw new RpcErrorException("MCP tools/call failed", response.error());
        }
        return response.result();
    }

    public List<RemoteViewportSummary> listViewports(ViewportServerRegistryService.RegisteredServer server) {
        RpcResponse response = callRpc(toEndpoint(server), "viewports/list", objectMapper.createObjectNode());
        if (response.error() != null) {
            throw new RpcErrorException("MCP viewports/list failed", response.error());
        }
        JsonNode viewports = response.result() == null ? null : response.result().path("viewports");
        if (viewports == null || !viewports.isArray()) {
            return List.of();
        }
        List<RemoteViewportSummary> summaries = new ArrayList<>();
        for (JsonNode node : viewports) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String viewportKey = text(node.get("viewportKey"));
            String viewportType = text(node.get("viewportType"));
            if (!StringUtils.hasText(viewportKey) || !StringUtils.hasText(viewportType)) {
                log.warn("Skip invalid viewport summary from server '{}' due to missing required viewport fields", server.serverKey());
                continue;
            }
            summaries.add(new RemoteViewportSummary(viewportKey, viewportType));
        }
        return List.copyOf(summaries);
    }

    public RemoteViewportPayload getViewport(
            ViewportServerRegistryService.RegisteredServer server,
            String viewportKey
    ) {
        ObjectNode params = objectMapper.createObjectNode();
        if (StringUtils.hasText(viewportKey)) {
            params.put("viewportKey", viewportKey.trim());
        }
        RpcResponse response = callRpc(toEndpoint(server), "viewports/get", params);
        if (response.error() != null) {
            throw new RpcErrorException("MCP viewports/get failed", response.error());
        }
        JsonNode result = response.result();
        if (result == null || result.isNull() || !result.isObject()) {
            throw new IllegalStateException("MCP viewports/get returned empty payload");
        }
        String viewportType = text(result.get("viewportType"));
        JsonNode payload = result.get("payload");
        if (!StringUtils.hasText(viewportType) || payload == null || payload.isNull()) {
            throw new IllegalStateException("MCP viewports/get returned invalid payload");
        }
        if ("html".equalsIgnoreCase(viewportType) && !payload.isTextual()) {
            throw new IllegalStateException("MCP html viewport payload must be string");
        }
        if ("qlc".equalsIgnoreCase(viewportType) && !payload.isObject()) {
            throw new IllegalStateException("MCP qlc viewport payload must be object");
        }
        return new RemoteViewportPayload(viewportType.trim(), payload);
    }

    private RpcResponse callRpc(ServerEndpoint server, String method, ObjectNode params) {
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
                    if (StringUtils.hasText(body)) {
                        log.debug(
                                "Failed to parse MCP response payload serverKey={}, method={}, fallback=empty payload body={}",
                                server.serverKey(),
                                method,
                                abbreviateForLog(body)
                        );
                    }
                    throw new IllegalStateException("MCP call returned empty payload");
                }
                JsonNode result = payload.get("result");
                RpcError error = parseRpcError(payload.get("error"));
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

    private WebClient buildClient(ServerEndpoint server) {
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

    private RpcError parseRpcError(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        Integer code = node.has("code") && node.get("code").canConvertToInt() ? node.get("code").asInt() : null;
        String message = text(node.get("message"));
        JsonNode data = node.get("data");
        return new RpcError(code, message, data);
    }

    JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse JSON payload: " + ex.getMessage(), ex);
        }
    }

    private ServerEndpoint toEndpoint(McpServerRegistryService.RegisteredServer server) {
        return new ServerEndpoint(
                server.serverKey(),
                server.endpointUrl(),
                server.headers(),
                server.connectTimeoutMs(),
                server.readTimeoutMs(),
                server.retry()
        );
    }

    private ServerEndpoint toEndpoint(ViewportServerRegistryService.RegisteredServer server) {
        return new ServerEndpoint(
                server.serverKey(),
                server.endpointUrl(),
                server.headers(),
                server.connectTimeoutMs(),
                server.readTimeoutMs(),
                server.retry()
        );
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

    private String abbreviateForLog(String body) {
        String normalized = body == null ? "" : body.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 400) {
            return normalized;
        }
        return normalized.substring(0, 400) + "...";
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

    public record RemoteViewportSummary(
            String viewportKey,
            String viewportType
    ) {
    }

    public record RemoteViewportPayload(
            String viewportType,
            JsonNode payload
    ) {
    }

    private record RpcResponse(
            JsonNode result,
            RpcError error
    ) {
    }

    public record RpcError(
            Integer code,
            String message,
            JsonNode data
    ) {
        public boolean isMethodNotFound() {
            return code != null && code == -32601;
        }

        public boolean isInvalidParams() {
            return code != null && code == -32602;
        }
    }

    public static final class RpcErrorException extends IllegalStateException {
        private final RpcError error;

        public RpcErrorException(String prefix, RpcError error) {
            super(buildMessage(prefix, error));
            this.error = error;
        }

        public RpcError error() {
            return error;
        }

        private static String buildMessage(String prefix, RpcError error) {
            if (error == null) {
                return prefix;
            }
            String codePart = error.code() == null ? "" : "[" + error.code() + "] ";
            String message = StringUtils.hasText(error.message()) ? error.message() : "unknown rpc error";
            return prefix + ": " + codePart + message;
        }
    }

    private record ServerEndpoint(
            String serverKey,
            String endpointUrl,
            Map<String, String> headers,
            int connectTimeoutMs,
            int readTimeoutMs,
            int retry
    ) {
    }
}
