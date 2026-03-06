package com.linlay.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.tool.CapabilityDescriptor;
import com.linlay.agentplatform.tool.CapabilityKind;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class McpCapabilitySyncService {

    private static final Logger log = LoggerFactory.getLogger(McpCapabilitySyncService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpProperties properties;
    private final McpServerRegistryService serverRegistryService;
    private final McpServerAvailabilityGate availabilityGate;
    private final McpStreamableHttpClient streamableHttpClient;
    private final ObjectMapper objectMapper;
    private final Object refreshLock = new Object();

    private volatile Map<String, CapabilityDescriptor> capabilitiesByName = Map.of();
    private volatile Map<String, String> aliasToCanonical = Map.of();
    private volatile Map<String, ServerCapabilitySnapshot> snapshotsByServerKey = Map.of();

    public McpCapabilitySyncService(
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
        refreshCapabilities();
    }

    public CatalogDiff refreshCapabilities() {
        synchronized (refreshLock) {
            Map<String, CapabilityDescriptor> before = capabilitiesByName;
            if (!properties.isEnabled()) {
                capabilitiesByName = Map.of();
                aliasToCanonical = Map.of();
                snapshotsByServerKey = Map.of();
                availabilityGate.prune(Set.of());
                return CatalogDiff.between(before, capabilitiesByName);
            }

            List<McpServerRegistryService.RegisteredServer> servers = serverRegistryService.list();
            long registryVersion = serverRegistryService.currentVersion();

            Set<String> activeServerKeys = new HashSet<>();
            for (McpServerRegistryService.RegisteredServer server : servers) {
                activeServerKeys.add(normalize(server.serverKey()));
            }
            availabilityGate.prune(activeServerKeys);

            Map<String, ServerCapabilitySnapshot> nextSnapshots = new LinkedHashMap<>(snapshotsByServerKey);

            for (McpServerRegistryService.RegisteredServer server : servers) {
                String serverKey = normalize(server.serverKey());
                if (availabilityGate.isBlocked(serverKey, registryVersion)) {
                    log.debug("Skip MCP capability sync for blocked server '{}' at version={}", serverKey, registryVersion);
                    continue;
                }
                try {
                    streamableHttpClient.initialize(server, properties.getProtocolVersion());
                    List<McpStreamableHttpClient.McpToolDefinition> tools = streamableHttpClient.listTools(server);
                    nextSnapshots.put(serverKey, buildServerSnapshot(server, tools));
                    availabilityGate.markSuccess(serverKey);
                } catch (Exception ex) {
                    availabilityGate.markFailure(serverKey, registryVersion);
                    log.warn("Failed to sync MCP capabilities from server '{}': {}",
                            server.serverKey(),
                            summarizeException(ex));
                    if (log.isDebugEnabled()) {
                        log.debug("MCP capability sync stack server='{}'", server.serverKey(), ex);
                    }
                }
            }

            nextSnapshots.keySet().removeIf(serverKey -> !activeServerKeys.contains(serverKey));

            Map<String, CapabilityDescriptor> loaded = new LinkedHashMap<>();
            Map<String, String> loadedAlias = new LinkedHashMap<>();
            Set<String> conflicts = new HashSet<>();
            for (McpServerRegistryService.RegisteredServer server : servers) {
                ServerCapabilitySnapshot snapshot = nextSnapshots.get(normalize(server.serverKey()));
                if (snapshot == null) {
                    continue;
                }
                mergeSnapshot(snapshot, loaded, loadedAlias, conflicts);
            }

            snapshotsByServerKey = Map.copyOf(nextSnapshots);
            capabilitiesByName = Map.copyOf(loaded);
            aliasToCanonical = Map.copyOf(loadedAlias);
            CatalogDiff diff = CatalogDiff.between(before, capabilitiesByName);
            log.debug(
                    "Refreshed MCP capability cache, size={}, aliases={}, changed={}",
                    capabilitiesByName.size(),
                    aliasToCanonical.size(),
                    diff.changedKeys().size()
            );
            return diff;
        }
    }

    public List<CapabilityDescriptor> list() {
        return capabilitiesByName.values().stream()
                .sorted(java.util.Comparator.comparing(CapabilityDescriptor::name))
                .toList();
    }

    public Optional<CapabilityDescriptor> find(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return Optional.empty();
        }
        String normalized = normalize(toolName);
        CapabilityDescriptor direct = capabilitiesByName.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        String canonical = aliasToCanonical.get(normalized);
        if (!StringUtils.hasText(canonical)) {
            return Optional.empty();
        }
        return Optional.ofNullable(capabilitiesByName.get(canonical));
    }

    public Optional<String> resolveAlias(String maybeAlias) {
        if (!StringUtils.hasText(maybeAlias)) {
            return Optional.empty();
        }
        String canonical = aliasToCanonical.get(normalize(maybeAlias));
        return StringUtils.hasText(canonical) ? Optional.of(canonical) : Optional.empty();
    }

    private ServerCapabilitySnapshot buildServerSnapshot(
            McpServerRegistryService.RegisteredServer server,
            List<McpStreamableHttpClient.McpToolDefinition> tools
    ) {
        Map<String, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, String> aliasToCanonical = new LinkedHashMap<>();

        if (tools == null) {
            return new ServerCapabilitySnapshot(Map.copyOf(descriptors), Map.copyOf(aliasToCanonical));
        }

        for (McpStreamableHttpClient.McpToolDefinition tool : tools) {
            String toolName = normalize(tool.name());
            if (!StringUtils.hasText(toolName)) {
                continue;
            }
            if (descriptors.containsKey(toolName)) {
                log.warn("Duplicate MCP capability '{}' from server '{}', keep first", toolName, server.serverKey());
                continue;
            }
            String afterCallHint = normalizeText(tool.afterCallHint());

            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    toolName,
                    StringUtils.hasText(tool.description()) ? tool.description().trim() : "",
                    afterCallHint,
                    toParameters(tool.inputSchema()),
                    false,
                    true,
                    CapabilityKind.BACKEND,
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
        return new ServerCapabilitySnapshot(Map.copyOf(descriptors), Map.copyOf(aliasToCanonical));
    }

    private void mergeSnapshot(
            ServerCapabilitySnapshot snapshot,
            Map<String, CapabilityDescriptor> loaded,
            Map<String, String> loadedAlias,
            Set<String> conflicts
    ) {
        List<Map.Entry<String, CapabilityDescriptor>> descriptorEntries = new ArrayList<>(snapshot.capabilitiesByName().entrySet());
        descriptorEntries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (Map.Entry<String, CapabilityDescriptor> entry : descriptorEntries) {
            String toolName = entry.getKey();
            if (!StringUtils.hasText(toolName) || conflicts.contains(toolName)) {
                continue;
            }
            CapabilityDescriptor descriptor = entry.getValue();
            CapabilityDescriptor existing = loaded.putIfAbsent(toolName, descriptor);
            if (existing != null) {
                loaded.remove(toolName);
                conflicts.add(toolName);
                log.warn("Duplicate MCP capability '{}' from '{}' and '{}', both skipped",
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

    private record ServerCapabilitySnapshot(
            Map<String, CapabilityDescriptor> capabilitiesByName,
            Map<String, String> aliasToCanonical
    ) {
    }
}
