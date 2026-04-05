package com.linlay.agentplatform.integration.viewport;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.util.CatalogDiff;
import com.linlay.agentplatform.integration.mcp.McpStreamableHttpClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ViewportSyncService {

    private static final Logger log = LoggerFactory.getLogger(ViewportSyncService.class);

    private final ViewportServerProperties properties;
    private final ViewportServerRegistryService serverRegistryService;
    private final ViewportServerAvailabilityGate availabilityGate;
    private final McpStreamableHttpClient streamableHttpClient;
    private final Object refreshLock = new Object();

    private volatile Map<String, RemoteViewportBinding> viewportByKey = Map.of();
    private volatile Map<String, ServerViewportSnapshot> snapshotsByServerKey = Map.of();

    public ViewportSyncService(
            ViewportServerProperties properties,
            ViewportServerRegistryService serverRegistryService,
            ViewportServerAvailabilityGate availabilityGate,
            McpStreamableHttpClient streamableHttpClient
    ) {
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.streamableHttpClient = streamableHttpClient;
    }

    @PostConstruct
    public void initialize() {
        refreshViewports();
    }

    public CatalogDiff refreshViewports() {
        return refreshViewportsInternal(null);
    }

    public CatalogDiff refreshViewportsForServers(Collection<String> serverKeys) {
        if (serverKeys == null || serverKeys.isEmpty()) {
            return new CatalogDiff(Set.of(), Set.of(), Set.of());
        }
        Set<String> normalizedKeys = new HashSet<>();
        for (String serverKey : serverKeys) {
            String normalized = normalize(serverKey);
            if (StringUtils.hasText(normalized)) {
                normalizedKeys.add(normalized);
            }
        }
        if (normalizedKeys.isEmpty()) {
            return new CatalogDiff(Set.of(), Set.of(), Set.of());
        }
        return refreshViewportsInternal(Set.copyOf(normalizedKeys));
    }

    public Optional<RemoteViewportBinding> findViewport(String viewportKey) {
        if (!StringUtils.hasText(viewportKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(viewportByKey.get(normalize(viewportKey)));
    }

    public List<RemoteViewportBinding> list() {
        return viewportByKey.values().stream()
                .sorted(Comparator.comparing(RemoteViewportBinding::viewportKey))
                .toList();
    }

    private CatalogDiff refreshViewportsInternal(Set<String> targetServerKeys) {
        synchronized (refreshLock) {
            Map<String, RemoteViewportBinding> before = viewportByKey;
            if (!properties.isEnabled()) {
                viewportByKey = Map.of();
                snapshotsByServerKey = Map.of();
                availabilityGate.prune(Set.of());
                return CatalogDiff.between(before, viewportByKey);
            }

            List<ViewportServerRegistryService.RegisteredServer> servers = serverRegistryService.list();
            Set<String> activeServerKeys = new HashSet<>();
            for (ViewportServerRegistryService.RegisteredServer server : servers) {
                activeServerKeys.add(normalize(server.serverKey()));
            }
            availabilityGate.prune(activeServerKeys);

            Map<String, ServerViewportSnapshot> nextSnapshots = new LinkedHashMap<>(snapshotsByServerKey);
            Set<String> selectedServerKeys = targetServerKeys == null
                    ? activeServerKeys
                    : activeServerKeys.stream().filter(targetServerKeys::contains).collect(java.util.stream.Collectors.toSet());

            for (ViewportServerRegistryService.RegisteredServer server : servers) {
                String serverKey = normalize(server.serverKey());
                if (!selectedServerKeys.contains(serverKey)) {
                    continue;
                }
                try {
                    streamableHttpClient.initialize(server, properties.getProtocolVersion());
                    List<McpStreamableHttpClient.RemoteViewportSummary> summaries = streamableHttpClient.listViewports(server);
                    nextSnapshots.put(serverKey, buildServerSnapshot(server, summaries));
                    availabilityGate.markSuccess(serverKey);
                } catch (McpStreamableHttpClient.RpcErrorException ex) {
                    if (isUnsupportedViewports(ex.error())) {
                        log.info("Viewport server '{}' does not support viewports protocol, skip registration", server.serverKey());
                        nextSnapshots.put(serverKey, new ServerViewportSnapshot(Map.of()));
                        availabilityGate.markSuccess(serverKey);
                        continue;
                    }
                    availabilityGate.markFailure(serverKey);
                    log.warn("Failed to sync viewport capabilities from server '{}': {}",
                            server.serverKey(),
                            summarizeException(ex));
                    if (log.isDebugEnabled()) {
                        log.debug("Viewport sync stack server='{}'", server.serverKey(), ex);
                    }
                } catch (Exception ex) {
                    availabilityGate.markFailure(serverKey);
                    log.warn("Failed to sync viewport capabilities from server '{}': {}",
                            server.serverKey(),
                            summarizeException(ex));
                    if (log.isDebugEnabled()) {
                        log.debug("Viewport sync stack server='{}'", server.serverKey(), ex);
                    }
                }
            }

            nextSnapshots.keySet().removeIf(serverKey -> !activeServerKeys.contains(serverKey));
            return publishSnapshots(before, servers, nextSnapshots);
        }
    }

    private CatalogDiff publishSnapshots(
            Map<String, RemoteViewportBinding> before,
            List<ViewportServerRegistryService.RegisteredServer> servers,
            Map<String, ServerViewportSnapshot> nextSnapshots
    ) {
        Map<String, RemoteViewportBinding> loaded = new LinkedHashMap<>();
        Set<String> viewportConflicts = new HashSet<>();
        for (ViewportServerRegistryService.RegisteredServer server : servers) {
            ServerViewportSnapshot snapshot = nextSnapshots.get(normalize(server.serverKey()));
            if (snapshot == null) {
                continue;
            }
            mergeSnapshot(snapshot, loaded, viewportConflicts);
        }

        snapshotsByServerKey = Map.copyOf(nextSnapshots);
        viewportByKey = Map.copyOf(loaded);
        CatalogDiff diff = CatalogDiff.between(before, viewportByKey);
        log.debug("Refreshed viewport registry, size={}, changed={}", viewportByKey.size(), diff.changedKeys().size());
        return diff;
    }

    private ServerViewportSnapshot buildServerSnapshot(
            ViewportServerRegistryService.RegisteredServer server,
            List<McpStreamableHttpClient.RemoteViewportSummary> summaries
    ) {
        Map<String, RemoteViewportBinding> viewportsByKey = new LinkedHashMap<>();
        if (summaries == null) {
            return new ServerViewportSnapshot(Map.of());
        }
        for (McpStreamableHttpClient.RemoteViewportSummary summary : summaries) {
            String viewportKey = normalize(summary.viewportKey());
            if (!StringUtils.hasText(viewportKey)) {
                continue;
            }
            if (viewportsByKey.containsKey(viewportKey)) {
                log.warn("Duplicate viewport '{}' from server '{}', keep first", viewportKey, server.serverKey());
                continue;
            }
            viewportsByKey.put(viewportKey, new RemoteViewportBinding(
                    viewportKey,
                    normalizeText(summary.viewportType()),
                    normalize(server.serverKey())
            ));
        }
        return new ServerViewportSnapshot(Map.copyOf(viewportsByKey));
    }

    private void mergeSnapshot(
            ServerViewportSnapshot snapshot,
            Map<String, RemoteViewportBinding> loadedViewports,
            Set<String> viewportConflicts
    ) {
        List<Map.Entry<String, RemoteViewportBinding>> viewportEntries = new ArrayList<>(snapshot.viewportsByKey().entrySet());
        viewportEntries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (Map.Entry<String, RemoteViewportBinding> entry : viewportEntries) {
            String viewportKey = entry.getKey();
            if (!StringUtils.hasText(viewportKey) || viewportConflicts.contains(viewportKey)) {
                continue;
            }
            RemoteViewportBinding binding = entry.getValue();
            RemoteViewportBinding existing = loadedViewports.putIfAbsent(viewportKey, binding);
            if (existing != null && !existing.serverKey().equals(binding.serverKey())) {
                loadedViewports.remove(viewportKey);
                viewportConflicts.add(viewportKey);
                log.warn("Duplicate viewport '{}' from '{}' and '{}', both skipped",
                        viewportKey,
                        existing.serverKey(),
                        binding.serverKey());
            }
        }
    }

    private boolean isUnsupportedViewports(McpStreamableHttpClient.RpcError error) {
        return error != null && error.isMethodNotFound();
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String raw) {
        return raw == null ? "" : raw.trim();
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

    private record ServerViewportSnapshot(
            Map<String, RemoteViewportBinding> viewportsByKey
    ) {
    }

    public record RemoteViewportBinding(
            String viewportKey,
            String viewportType,
            String serverKey
    ) {
    }
}
