package com.linlay.agentplatform.service.viewport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
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
public class ViewportServerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ViewportServerRegistryService.class);
    private static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    private final ObjectMapper objectMapper;
    private final ViewportServerProperties properties;
    private final Object reloadLock = new Object();
    private volatile Map<String, RegisteredServer> byKey = Map.of();
    private volatile long registryVersion = 0L;

    public ViewportServerRegistryService(
            ObjectMapper objectMapper,
            ViewportServerProperties properties
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
            Path dir = Path.of(properties.getRegistry().getExternalDir()).toAbsolutePath().normalize();
            Map<String, RegisteredServer> merged = new LinkedHashMap<>();
            for (RemoteServerConfigSupport.ServerSpec spec : RemoteServerConfigSupport.loadServerSpecs(dir, objectMapper, log)) {
                normalizeServer(spec).ifPresent(server -> merged.putIfAbsent(server.serverKey(), server));
            }
            byKey = Map.copyOf(merged);
            registryVersion++;
            log.debug("Refreshed viewport server registry, size={}", byKey.size());
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
            log.warn("Skip viewport server '{}' due to empty baseUrl", serverKey);
            return Optional.empty();
        }
        String endpointPath = RemoteServerConfigSupport.normalizeEndpointPath(raw.endpointPath(), "/mcp");
        Map<String, String> headers = RemoteServerConfigSupport.normalizeStringMap(raw.headers());
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
                headers,
                connectTimeoutMs,
                readTimeoutMs,
                retry
        ));
    }

    public record RegisteredServer(
            String serverKey,
            String baseUrl,
            String endpointPath,
            Map<String, String> headers,
            int connectTimeoutMs,
            int readTimeoutMs,
            int retry
    ) {
        public RegisteredServer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
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
