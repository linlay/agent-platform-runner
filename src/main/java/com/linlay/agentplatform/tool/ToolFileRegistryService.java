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
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".json", ".yml", ".yaml");

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
            if (!isSupported(file)) {
                continue;
            }
            parseFile(file, loaded, conflicts);
        }
    }

    private void parseFile(
            Path file,
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

        if (!root.isObject()) {
            log.warn("Skip invalid tool file without object root: {}", file);
            return;
        }
        if (root.has("tools") && root.get("tools").isArray()) {
            log.warn("Skip legacy multi-tool file; tools[] is no longer supported: {}", file);
            return;
        }

        String type = normalize(root.path("type").asText(""));
        if (!"function".equals(type)) {
            log.warn("Skip non-function tool file: {}", file);
            return;
        }
        String name = normalizeName(root.path("name").asText(""));
        if (name.isBlank()) {
            log.warn("Skip tool file without name: {}", file);
            return;
        }
        if (conflicts.contains(name)) {
            return;
        }

        Map<String, Object> parameters = parseParameters(root.has("inputSchema") ? root.get("inputSchema") : root.get("parameters"));
        String description = normalize(root.path("description").asText(""));
        String afterCallHint = normalize(root.path("afterCallHint").asText(""));
        Boolean strict = root.has("strict") ? root.path("strict").asBoolean(false) : null;
        Boolean clientVisible = root.has("clientVisible") ? root.path("clientVisible").asBoolean(true) : null;
        Boolean toolAction = root.has("toolAction") ? root.path("toolAction").asBoolean(false) : null;
        String toolType = root.has("toolType") && root.get("toolType").isTextual()
                ? root.get("toolType").asText()
                : null;
        String toolApi = root.has("toolApi") && root.get("toolApi").isTextual()
                ? root.get("toolApi").asText()
                : null;
        String viewportKey = root.has("viewportKey") && root.get("viewportKey").isTextual()
                ? root.get("viewportKey").asText()
                : null;

        ToolDescriptor descriptor = new ToolDescriptor(
                name,
                description,
                afterCallHint,
                parameters,
                strict,
                clientVisible,
                toolAction,
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

    private boolean isSupported(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_SUFFIXES.stream().anyMatch(fileName::endsWith);
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
