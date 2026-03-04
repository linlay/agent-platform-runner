package com.linlay.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.McpProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@DependsOn("runtimeResourceSyncService")
public class McpServerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    private final ObjectMapper objectMapper;
    private final McpProperties properties;
    private final Object reloadLock = new Object();
    private volatile Map<String, RegisteredServer> byKey = Map.of();
    private volatile long registryVersion = 0L;

    public McpServerRegistryService(
            ObjectMapper objectMapper,
            McpProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        refreshServers();
    }

    public void refreshServers() {
        synchronized (reloadLock) {
            Map<String, RegisteredServer> merged = new LinkedHashMap<>();
            Map<String, RegisteredServer> dynamic = loadDynamicServers();
            dynamic.forEach(merged::putIfAbsent);
            if (!properties.getServers().isEmpty()) {
                log.warn("agent.mcp.servers is deprecated and ignored; use agent.mcp.registry.external-dir files instead");
            }

            byKey = Map.copyOf(merged);
            registryVersion++;
            log.debug("Refreshed MCP server registry, size={}", byKey.size());
        }
    }

    public long currentVersion() {
        return registryVersion;
    }

    public List<RegisteredServer> list() {
        return byKey.values().stream()
                .sorted(Comparator.comparing(RegisteredServer::serverKey))
                .toList();
    }

    public Optional<RegisteredServer> find(String serverKey) {
        if (!StringUtils.hasText(serverKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(serverKey.trim().toLowerCase(Locale.ROOT)));
    }

    private Map<String, RegisteredServer> loadDynamicServers() {
        Path dir = Path.of(properties.getRegistry().getExternalDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Map.of();
        }

        Map<String, RegisteredServer> loaded = new LinkedHashMap<>();
        for (Path file : sortedFiles(dir)) {
            if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            for (McpProperties.Server rawServer : parseServerFile(file)) {
                normalizeServer(rawServer).ifPresent(server -> loaded.putIfAbsent(server.serverKey(), server));
            }
        }
        return loaded;
    }

    private List<McpProperties.Server> parseServerFile(Path file) {
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            if (root.isArray()) {
                List<McpProperties.Server> servers = new ArrayList<>();
                for (JsonNode item : root) {
                    if (item == null || !item.isObject()) {
                        continue;
                    }
                    servers.add(toServer(item));
                }
                return servers;
            }
            if (root.isObject()) {
                if (root.has("servers") && root.get("servers").isArray()) {
                    List<McpProperties.Server> servers = new ArrayList<>();
                    for (JsonNode item : root.get("servers")) {
                        if (item == null || !item.isObject()) {
                            continue;
                        }
                        servers.add(toServer(item));
                    }
                    return servers;
                }
                return List.of(toServer(root));
            }
        } catch (Exception ex) {
            log.warn("Skip invalid mcp server config file: {}", file, ex);
        }
        return List.of();
    }

    private McpProperties.Server toServer(JsonNode node) {
        Map<String, Object> raw = objectMapper.convertValue(node, MAP_TYPE);
        McpProperties.Server server = new McpProperties.Server();
        Object serverKey = firstNonNull(raw.get("serverKey"), raw.get("server-key"), raw.get("key"));
        Object baseUrl = firstNonNull(raw.get("baseUrl"), raw.get("base-url"), raw.get("url"));
        Object endpointPath = firstNonNull(raw.get("endpointPath"), raw.get("endpoint-path"), raw.get("path"));
        Object toolPrefix = firstNonNull(raw.get("toolPrefix"), raw.get("tool-prefix"));
        Object enabled = raw.get("enabled");
        Object connectTimeoutMs = firstNonNull(raw.get("connectTimeoutMs"), raw.get("connect-timeout-ms"));
        Object readTimeoutMs = firstNonNull(raw.get("readTimeoutMs"), raw.get("read-timeout-ms"));
        Object retry = raw.get("retry");

        server.setServerKey(serverKey == null ? null : String.valueOf(serverKey));
        server.setBaseUrl(baseUrl == null ? null : String.valueOf(baseUrl));
        server.setEndpointPath(endpointPath == null ? null : String.valueOf(endpointPath));
        server.setToolPrefix(toolPrefix == null ? null : String.valueOf(toolPrefix));
        if (enabled != null) {
            server.setEnabled(Boolean.parseBoolean(String.valueOf(enabled)));
        }
        if (connectTimeoutMs != null) {
            server.setConnectTimeoutMs(parseInt(connectTimeoutMs));
        }
        if (readTimeoutMs != null) {
            server.setReadTimeoutMs(parseInt(readTimeoutMs));
        }
        if (retry != null) {
            server.setRetry(parseInt(retry));
        }

        if (raw.get("headers") instanceof Map<?, ?> headers) {
            Map<String, String> normalizedHeaders = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                normalizedHeaders.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            server.setHeaders(normalizedHeaders);
        }

        if (raw.get("aliasMap") instanceof Map<?, ?> aliasMap) {
            Map<String, String> normalizedAlias = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : aliasMap.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                normalizedAlias.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            server.setAliasMap(normalizedAlias);
        }

        return server;
    }

    private Optional<RegisteredServer> normalizeServer(McpProperties.Server raw) {
        if (raw == null || !raw.isEnabled()) {
            return Optional.empty();
        }
        String serverKey = normalizeKey(raw.getServerKey());
        if (!StringUtils.hasText(serverKey)) {
            return Optional.empty();
        }
        String baseUrl = normalizeText(raw.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("Skip MCP server '{}' due to empty baseUrl", serverKey);
            return Optional.empty();
        }
        String endpointPath = normalizeEndpointPath(raw.getEndpointPath());
        String toolPrefix = normalizeText(raw.getToolPrefix());

        Map<String, String> headers = normalizeStringMap(raw.getHeaders());
        Map<String, String> aliasMap = normalizeAliasMap(raw.getAliasMap());

        int connectTimeoutMs = raw.getConnectTimeoutMs() != null
                ? Math.max(1, raw.getConnectTimeoutMs())
                : Math.max(1, properties.getConnectTimeoutMs());
        int readTimeoutMs = raw.getReadTimeoutMs() != null
                ? Math.max(1, raw.getReadTimeoutMs())
                : DEFAULT_READ_TIMEOUT_MS;
        int retry = raw.getRetry() != null ? Math.max(0, raw.getRetry()) : Math.max(0, properties.getRetry());

        return Optional.of(new RegisteredServer(
                serverKey,
                baseUrl,
                endpointPath,
                toolPrefix,
                headers,
                aliasMap,
                connectTimeoutMs,
                readTimeoutMs,
                retry
        ));
    }

    private List<Path> sortedFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("Cannot list mcp server config files from {}", dir, ex);
            return List.of();
        }
    }

    private String normalizeKey(String raw) {
        String text = normalizeText(raw);
        return StringUtils.hasText(text) ? text.toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private String normalizeEndpointPath(String raw) {
        String normalized = normalizeText(raw);
        if (!StringUtils.hasText(normalized)) {
            return "/mcp";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private Map<String, String> normalizeStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeText(entry.getKey());
            String value = normalizeText(entry.getValue());
            if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                continue;
            }
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private Map<String, String> normalizeAliasMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String alias = normalizeText(entry.getKey()).toLowerCase(Locale.ROOT);
            String target = normalizeText(entry.getValue()).toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(alias) || !StringUtils.hasText(target)) {
                continue;
            }
            normalized.put(alias, target);
        }
        return Map.copyOf(normalized);
    }

    private Integer parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record RegisteredServer(
            String serverKey,
            String baseUrl,
            String endpointPath,
            String toolPrefix,
            Map<String, String> headers,
            Map<String, String> aliasMap,
            int connectTimeoutMs,
            int readTimeoutMs,
            int retry
    ) {
        public RegisteredServer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            aliasMap = aliasMap == null ? Map.of() : Map.copyOf(aliasMap);
        }

        public String endpointUrl() {
            if (baseUrl.endsWith("/") && endpointPath.startsWith("/")) {
                return baseUrl.substring(0, baseUrl.length() - 1) + endpointPath;
            }
            if (!baseUrl.endsWith("/") && !endpointPath.startsWith("/")) {
                return baseUrl + "/" + endpointPath;
            }
            return baseUrl + endpointPath;
        }
    }
}
