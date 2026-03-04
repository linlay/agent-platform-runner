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
    private final McpStreamableHttpClient streamableHttpClient;
    private final ObjectMapper objectMapper;
    private final Object refreshLock = new Object();

    private volatile Map<String, CapabilityDescriptor> capabilitiesByName = Map.of();
    private volatile Map<String, String> aliasToCanonical = Map.of();

    public McpCapabilitySyncService(
            McpProperties properties,
            McpServerRegistryService serverRegistryService,
            McpStreamableHttpClient streamableHttpClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.streamableHttpClient = streamableHttpClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        refreshCapabilities();
    }

    public void refreshCapabilities() {
        synchronized (refreshLock) {
            if (!properties.isEnabled()) {
                capabilitiesByName = Map.of();
                aliasToCanonical = Map.of();
                return;
            }

            Map<String, CapabilityDescriptor> loaded = new LinkedHashMap<>();
            Map<String, String> loadedAlias = new LinkedHashMap<>();
            Set<String> conflicts = new HashSet<>();

            for (McpServerRegistryService.RegisteredServer server : serverRegistryService.list()) {
                try {
                    streamableHttpClient.initialize(server, properties.getProtocolVersion());
                    List<McpStreamableHttpClient.McpToolDefinition> tools = streamableHttpClient.listTools(server);
                    mergeTools(server, tools, loaded, loadedAlias, conflicts);
                } catch (Exception ex) {
                    log.warn("Failed to sync MCP capabilities from server '{}'", server.serverKey(), ex);
                }
            }

            capabilitiesByName = Map.copyOf(loaded);
            aliasToCanonical = Map.copyOf(loadedAlias);
            log.debug("Refreshed MCP capability cache, size={}, aliases={}", capabilitiesByName.size(), aliasToCanonical.size());
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

    private void mergeTools(
            McpServerRegistryService.RegisteredServer server,
            List<McpStreamableHttpClient.McpToolDefinition> tools,
            Map<String, CapabilityDescriptor> loaded,
            Map<String, String> loadedAlias,
            Set<String> conflicts
    ) {
        for (McpStreamableHttpClient.McpToolDefinition tool : tools) {
            String toolName = normalize(tool.name());
            if (!StringUtils.hasText(toolName) || conflicts.contains(toolName)) {
                continue;
            }
            String afterCallHint = normalizeText(tool.afterCallHint());

            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    toolName,
                    StringUtils.hasText(tool.description()) ? tool.description().trim() : "",
                    afterCallHint,
                    toParameters(tool.inputSchema()),
                    false,
                    CapabilityKind.BACKEND,
                    "function",
                    "mcp://" + server.serverKey() + "/" + toolName,
                    "mcp",
                    server.serverKey(),
                    null,
                    "mcp://" + server.serverKey()
            );

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

            registerAliases(server, toolName, tool.aliases(), loadedAlias);
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
}
