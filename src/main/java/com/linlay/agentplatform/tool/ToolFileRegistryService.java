package com.linlay.agentplatform.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.service.CatalogDiff;
import com.linlay.agentplatform.util.StringHelpers;

@Service
@DependsOn("runtimeResourceSyncService")
public class ToolFileRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolFileRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String BACKEND_SUFFIX = ".backend";
    private static final String ACTION_SUFFIX = ".action";
    private static final Set<String> FRONTEND_SUFFIXES = Set.of(".frontend");

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final ToolProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, ToolDescriptor> byName = Map.of();

    public ToolFileRegistryService(
            ObjectMapper objectMapper,
            ToolProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        refreshTools();
    }

    public List<ToolDescriptor> list() {
        return byName.values().stream()
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public Optional<ToolDescriptor> find(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(normalizeName(toolName)));
    }

    public CatalogDiff refreshTools() {
        synchronized (reloadLock) {
            Map<String, ToolDescriptor> before = byName;
            Map<String, ToolDescriptor> loaded = new LinkedHashMap<>();
            Set<String> conflicts = new HashSet<>();

            loadToolsDirectory(Path.of(properties.getExternalDir()).toAbsolutePath().normalize(), loaded, conflicts);

            byName = Map.copyOf(loaded);
            CatalogDiff diff = CatalogDiff.between(before, byName);
            log.debug("Refreshed tool file registry, size={}, changed={}", loaded.size(), diff.changedKeys().size());
            return diff;
        }
    }

    private void loadToolsDirectory(Path dir, Map<String, ToolDescriptor> loaded, Set<String> conflicts) {
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            log.warn("Configured tools directory is not a directory: {}", dir);
            return;
        }
        for (Path file : sortedFiles(dir)) {
            String fileName = file.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(BACKEND_SUFFIX)) {
                parseFile(file, ToolKind.BACKEND, "function", null, loaded, conflicts);
                continue;
            }
            if (lower.endsWith(ACTION_SUFFIX)) {
                parseFile(file, ToolKind.ACTION, "action", null, loaded, conflicts);
                continue;
            }
            String frontendSuffix = resolveFrontendSuffix(lower);
            if (frontendSuffix == null) {
                continue;
            }
            String viewportKey = fileName.substring(0, fileName.length() - frontendSuffix.length()).toLowerCase(Locale.ROOT);
            parseFile(file, ToolKind.FRONTEND, frontendSuffix.substring(1), viewportKey, loaded, conflicts);
        }
    }

    private void parseFile(
            Path file,
            ToolKind kind,
            String toolType,
            String viewportKey,
            Map<String, ToolDescriptor> loaded,
            Set<String> conflicts
    ) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(Files.readString(file));
        } catch (Exception ex) {
            log.warn("Skip invalid tool file: {}", file, ex);
            return;
        }

        JsonNode tools = root.path("tools");
        if (!tools.isArray()) {
            log.warn("Skip tool file without tools[]: {}", file);
            return;
        }

        for (JsonNode node : tools) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String type = normalize(node.path("type").asText(""));
            if (!"function".equals(type)) {
                continue;
            }
            String name = normalizeName(node.path("name").asText(""));
            if (name.isBlank()) {
                continue;
            }
            if (conflicts.contains(name)) {
                continue;
            }

            Map<String, Object> parameters = parseParameters(node.get("parameters"));
            String description = normalize(node.path("description").asText(""));
            String afterCallHint = normalize(node.path("afterCallHint").asText(""));
            Boolean strict = node.has("strict") ? node.path("strict").asBoolean(false) : null;
            Boolean clientVisible = node.has("clientVisible") ? node.path("clientVisible").asBoolean(true) : null;
            String toolApi = node.has("toolApi") && node.get("toolApi").isTextual()
                    ? node.get("toolApi").asText()
                    : null;

            ToolDescriptor descriptor = new ToolDescriptor(
                    name,
                    description,
                    afterCallHint,
                    parameters,
                    strict,
                    clientVisible,
                    kind,
                    toolType,
                    toolApi,
                    "local",
                    null,
                    viewportKey,
                    file.toString()
            );

            ToolDescriptor old = loaded.putIfAbsent(name, descriptor);
            if (old != null) {
                loaded.remove(name);
                conflicts.add(name);
                log.warn("Duplicate tool name '{}' found in {} and {}, both skipped", name, old.sourceFile(), file);
            }
        }
    }

    private List<Path> sortedFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException ex) {
            log.warn("Cannot list tool files from {}", dir, ex);
        }
        return files;
    }

    private String resolveFrontendSuffix(String fileName) {
        return FRONTEND_SUFFIXES.stream()
                .filter(fileName::endsWith)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> parseParameters(JsonNode node) {
        if (node == null || node.isNull()) {
            return defaultParameters();
        }
        if (!node.isObject()) {
            return defaultParameters();
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
            return converted == null || converted.isEmpty() ? defaultParameters() : converted;
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

    private String normalizeName(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }
}
