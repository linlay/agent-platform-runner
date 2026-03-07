package com.linlay.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
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
public class McpToolSyncService {

    private static final Logger log = LoggerFactory.getLogger(McpToolSyncService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpProperties properties;
    private final McpServerRegistryService serverRegistryService;
    private final McpServerAvailabilityGate availabilityGate;
    private final McpStreamableHttpClient streamableHttpClient;
    private final ObjectMapper objectMapper;
    private final Object refreshLock = new Object();

    private volatile Map<String, ToolDescriptor> toolsByName = Map.of();
    private volatile Map<String, String> aliasToCanonical = Map.of();
    private volatile Map<String, ServerToolSnapshot> snapshotsByServerKey = Map.of();

    public McpToolSyncService(
            McpProperties properties,
            McpServerRegistryService serverRegistryService,
            McpServerAvailabilityGate availabilityGate,
            McpStreamableHttpClient streamableHttpClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.streamableHttpClient = streamableHttpClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        refreshTools();
    }

    public CatalogDiff refreshTools() {
        return refreshToolsInternal(null);
    }

    public CatalogDiff refreshToolsForServers(Collection<String> serverKeys) {
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
        return refreshToolsInternal(Set.copyOf(normalizedKeys));
    }

    private CatalogDiff refreshToolsInternal(Set<String> targetServerKeys) {
        synchronized (refreshLock) {
            Map<String, ToolDescriptor> before = toolsByName;
            if (!properties.isEnabled()) {
                toolsByName = Map.of();
                aliasToCanonical = Map.of();
                snapshotsByServerKey = Map.of();
                availabilityGate.prune(Set.of());
                return CatalogDiff.between(before, toolsByName);
            }

            List<McpServerRegistryService.RegisteredServer> servers = serverRegistryService.list();
            Set<String> activeServerKeys = new HashSet<>();
            for (McpServerRegistryService.RegisteredServer server : servers) {
                activeServerKeys.add(normalize(server.serverKey()));
            }
            availabilityGate.prune(activeServerKeys);

            Map<String, ServerToolSnapshot> nextSnapshots = new LinkedHashMap<>(snapshotsByServerKey);
            Set<String> selectedServerKeys = targetServerKeys == null
                    ? activeServerKeys
                    : activeServerKeys.stream()
                            .filter(targetServerKeys::contains)
                            .collect(java.util.stream.Collectors.toSet());

            for (McpServerRegistryService.RegisteredServer server : servers) {
                String serverKey = normalize(server.serverKey());
                if (!selectedServerKeys.contains(serverKey)) {
                    continue;
                }
                try {
                    streamableHttpClient.initialize(server, properties.getProtocolVersion());
                    List<McpStreamableHttpClient.McpToolDefinition> tools = streamableHttpClient.listTools(server);
                    nextSnapshots.put(serverKey, buildServerSnapshot(server, tools));
                    availabilityGate.markSuccess(serverKey);
                } catch (Exception ex) {
                    availabilityGate.markFailure(serverKey);
                    log.warn("Failed to sync MCP capabilities from server '{}': {}",
                            server.serverKey(),
                            summarizeException(ex));
                    if (log.isDebugEnabled()) {
                        log.debug("MCP tool sync stack server='{}'", server.serverKey(), ex);
                    }
                }
            }

            nextSnapshots.keySet().removeIf(serverKey -> !activeServerKeys.contains(serverKey));
            return publishSnapshots(before, servers, nextSnapshots);
        }
    }

    public List<ToolDescriptor> list() {
        return toolsByName.values().stream()
                .sorted(java.util.Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public Optional<ToolDescriptor> find(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return Optional.empty();
        }
        String normalized = normalize(toolName);
        ToolDescriptor direct = toolsByName.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        String canonical = aliasToCanonical.get(normalized);
        if (!StringUtils.hasText(canonical)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolsByName.get(canonical));
    }

    public Optional<String> resolveAlias(String maybeAlias) {
        if (!StringUtils.hasText(maybeAlias)) {
            return Optional.empty();
        }
        String canonical = aliasToCanonical.get(normalize(maybeAlias));
        return StringUtils.hasText(canonical) ? Optional.of(canonical) : Optional.empty();
    }

    private CatalogDiff publishSnapshots(
            Map<String, ToolDescriptor> before,
            List<McpServerRegistryService.RegisteredServer> servers,
            Map<String, ServerToolSnapshot> nextSnapshots
    ) {
        Map<String, ToolDescriptor> loaded = new LinkedHashMap<>();
        Map<String, String> loadedAlias = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (McpServerRegistryService.RegisteredServer server : servers) {
            ServerToolSnapshot snapshot = nextSnapshots.get(normalize(server.serverKey()));
            if (snapshot == null) {
                continue;
            }
            mergeSnapshot(snapshot, loaded, loadedAlias, conflicts);
        }

        snapshotsByServerKey = Map.copyOf(nextSnapshots);
        toolsByName = Map.copyOf(loaded);
        aliasToCanonical = Map.copyOf(loadedAlias);
        CatalogDiff diff = CatalogDiff.between(before, toolsByName);
        log.debug(
                "Refreshed MCP tool cache, size={}, aliases={}, changed={}",
                toolsByName.size(),
                aliasToCanonical.size(),
                diff.changedKeys().size()
        );
        return diff;
    }

    private ServerToolSnapshot buildServerSnapshot(
            McpServerRegistryService.RegisteredServer server,
            List<McpStreamableHttpClient.McpToolDefinition> tools
    ) {
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, String> aliasToCanonical = new LinkedHashMap<>();

        if (tools == null) {
            return new ServerToolSnapshot(Map.copyOf(descriptors), Map.copyOf(aliasToCanonical));
        }

        for (McpStreamableHttpClient.McpToolDefinition tool : tools) {
            String toolName = normalize(tool.name());
            if (!StringUtils.hasText(toolName)) {
                continue;
            }
            if (descriptors.containsKey(toolName)) {
                log.warn("Duplicate MCP tool '{}' from server '{}', keep first", toolName, server.serverKey());
                continue;
            }
            String afterCallHint = normalizeText(tool.afterCallHint());

            ToolDescriptor descriptor = new ToolDescriptor(
                    toolName,
                    StringUtils.hasText(tool.description()) ? tool.description().trim() : "",
                    afterCallHint,
                    toParameters(tool.inputSchema()),
                    false,
                    true,
                    ToolKind.BACKEND,
                    "function",
                    "mcp://" + server.serverKey() + "/" + toolName,
                    "mcp",
                    normalize(server.serverKey()),
                    null,
                    "mcp://" + server.serverKey()
            );
            descriptors.put(toolName, descriptor);
            registerAliases(server, toolName, tool.aliases(), aliasToCanonical);
        }
        return new ServerToolSnapshot(Map.copyOf(descriptors), Map.copyOf(aliasToCanonical));
    }

    private void mergeSnapshot(
            ServerToolSnapshot snapshot,
            Map<String, ToolDescriptor> loaded,
            Map<String, String> loadedAlias,
            Set<String> conflicts
    ) {
        List<Map.Entry<String, ToolDescriptor>> descriptorEntries = new ArrayList<>(snapshot.toolsByName().entrySet());
        descriptorEntries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (Map.Entry<String, ToolDescriptor> entry : descriptorEntries) {
            String toolName = entry.getKey();
            if (!StringUtils.hasText(toolName) || conflicts.contains(toolName)) {
                continue;
            }
            ToolDescriptor descriptor = entry.getValue();
            ToolDescriptor existing = loaded.putIfAbsent(toolName, descriptor);
            if (existing != null) {
                loaded.remove(toolName);
                conflicts.add(toolName);
                log.warn("Duplicate MCP tool '{}' from '{}' and '{}', both skipped",
                        toolName,
                        existing.sourceKey(),
                        descriptor.sourceKey());
                continue;
            }

        }

        List<Map.Entry<String, String>> aliasEntries = new ArrayList<>(snapshot.aliasToCanonical().entrySet());
        aliasEntries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (Map.Entry<String, String> entry : aliasEntries) {
            String alias = entry.getKey();
            String canonical = entry.getValue();
            if (!StringUtils.hasText(alias) || !StringUtils.hasText(canonical)) {
                continue;
            }
            String existing = loadedAlias.putIfAbsent(alias, canonical);
            if (existing != null && !existing.equals(canonical)) {
                log.warn("Duplicate MCP alias '{}' for '{}' and '{}', keep first", alias, existing, canonical);
            }
        }
    }

    private void registerAliases(
            McpServerRegistryService.RegisteredServer server,
            String toolName,
            List<String> aliases,
            Map<String, String> loadedAlias
    ) {
        List<String> mergedAliases = new ArrayList<>();
        if (aliases != null) {
            mergedAliases.addAll(aliases);
        }
        for (Map.Entry<String, String> entry : server.aliasMap().entrySet()) {
            String alias = normalize(entry.getKey());
            String target = normalize(entry.getValue());
            if (toolName.equals(target)) {
                mergedAliases.add(alias);
            }
        }

        for (String rawAlias : mergedAliases) {
            String alias = normalize(rawAlias);
            if (!StringUtils.hasText(alias) || alias.equals(toolName)) {
                continue;
            }
            String existing = loadedAlias.putIfAbsent(alias, toolName);
            if (existing != null && !existing.equals(toolName)) {
                log.warn("Duplicate MCP alias '{}' for '{}' and '{}', keep first", alias, existing, toolName);
            }
        }
    }

    private Map<String, Object> toParameters(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull() || !schemaNode.isObject()) {
            return defaultParameters();
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(schemaNode, MAP_TYPE);
            if (converted == null || converted.isEmpty()) {
                return defaultParameters();
            }
            return Map.copyOf(converted);
        } catch (IllegalArgumentException ex) {
            return defaultParameters();
        }
    }

    private Map<String, Object> defaultParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", true
        );
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

    private record ServerToolSnapshot(
            Map<String, ToolDescriptor> toolsByName,
            Map<String, String> aliasToCanonical
    ) {
    }
}
