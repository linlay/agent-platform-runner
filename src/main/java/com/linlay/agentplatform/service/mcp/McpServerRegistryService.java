package com.linlay.agentplatform.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.util.RemoteServerConfigSupport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class McpServerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistryService.class);
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
        Map<String, RegisteredServer> loaded = new LinkedHashMap<>();
        for (RemoteServerConfigSupport.ServerSpec spec : RemoteServerConfigSupport.loadServerSpecs(dir, objectMapper, log)) {
            normalizeServer(spec).ifPresent(server -> loaded.putIfAbsent(server.serverKey(), server));
        }
        return loaded;
    }

    private Optional<RegisteredServer> normalizeServer(RemoteServerConfigSupport.ServerSpec raw) {
        if (raw == null || !raw.enabled()) {
            return Optional.empty();
        }
        String serverKey = RemoteServerConfigSupport.normalizeKey(raw.serverKey());
        if (!StringUtils.hasText(serverKey)) {
            return Optional.empty();
        }
        String baseUrl = RemoteServerConfigSupport.normalizeText(raw.baseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("Skip MCP server '{}' due to empty baseUrl", serverKey);
            return Optional.empty();
        }
        String endpointPath = RemoteServerConfigSupport.normalizeEndpointPath(raw.endpointPath(), "/mcp");
        String toolPrefix = RemoteServerConfigSupport.normalizeText(raw.toolPrefix());

        Map<String, String> headers = RemoteServerConfigSupport.normalizeStringMap(raw.headers());
        Map<String, String> aliasMap = RemoteServerConfigSupport.normalizeAliasMap(raw.aliasMap());

        int connectTimeoutMs = raw.connectTimeoutMs() != null
                ? Math.max(1, raw.connectTimeoutMs())
                : Math.max(1, properties.getConnectTimeoutMs());
        int readTimeoutMs = raw.readTimeoutMs() != null
                ? Math.max(1, raw.readTimeoutMs())
                : DEFAULT_READ_TIMEOUT_MS;
        int retry = raw.retry() != null ? Math.max(0, raw.retry()) : Math.max(0, properties.getRetry());

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
