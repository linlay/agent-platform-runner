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
import com.linlay.agentplatform.util.YamlCatalogSupport;

@Service
@DependsOn("runtimeResourceSyncService")
public class ToolFileRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolFileRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
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
        for (Path file : YamlCatalogSupport.selectYamlFiles(sortedFiles(dir), "tool", log)) {
            parseFile(file, loaded, conflicts);
        }
    }

    private void parseFile(
            Path file,
            Map<String, ToolDescriptor> loaded,
            Set<String> conflicts
    ) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (Exception ex) {
            log.warn("Skip invalid tool file: {}", file, ex);
            return;
        }
        YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                raw,
                List.of("name", "label", "description", "type")
        );
        if (headerError.isPresent()) {
            log.warn("Skip tool '{}' due to invalid header: {} ({})", file.getFileName(), headerError.value(), file);
            return;
        }
        JsonNode root;
        try {
            root = yamlMapper.readTree(raw);
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
        String name = normalize(root.path("name").asText(""));
        String normalizedName = normalizeName(name);
        if (normalizedName.isBlank()) {
            log.warn("Skip tool file without name: {}", file);
            return;
        }
        if (conflicts.contains(normalizedName)) {
            return;
        }

        Map<String, Object> parameters = parseInputSchema(root.get("inputSchema"));
        String label = readOptionalText(root, "label");
        String description = normalize(root.path("description").asText(""));
        String afterCallHint = normalize(root.path("afterCallHint").asText(""));
        Boolean strict = root.has("strict") ? root.path("strict").asBoolean(false) : null;
        Boolean clientVisible = root.has("clientVisible") ? root.path("clientVisible").asBoolean(true) : null;
        Boolean toolAction = root.has("toolAction") ? root.path("toolAction").asBoolean(false) : null;
        String toolType = root.has("toolType") && root.get("toolType").isTextual()
                ? root.get("toolType").asText()
                : null;
        String viewportKey = root.has("viewportKey") && root.get("viewportKey").isTextual()
                ? root.get("viewportKey").asText()
                : null;
        String validationError = ToolMetadataValidator.validate(
                type,
                name,
                description,
                label,
                parameters,
                toolAction,
                toolType,
                viewportKey
        );
        if (validationError != null) {
            if (!"function".equalsIgnoreCase(type)) {
                log.warn("Skip non-function tool file: {}", file);
            } else {
                log.warn("Skip invalid tool file {}: {}", file, validationError);
            }
            return;
        }

        ToolDescriptor descriptor = new ToolDescriptor(
                name,
                label,
                description,
                afterCallHint,
                parameters,
                strict,
                clientVisible,
                toolAction,
                toolType,
                "local",
                null,
                viewportKey,
                file.toString()
        );

        ToolDescriptor old = loaded.putIfAbsent(normalizedName, descriptor);
        if (old != null) {
            loaded.remove(normalizedName);
            conflicts.add(normalizedName);
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

    private Map<String, Object> parseInputSchema(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
            return converted == null || converted.isEmpty() ? null : converted;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String readOptionalText(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || !root.has(fieldName)) {
            return null;
        }
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private String normalizeName(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }
}
