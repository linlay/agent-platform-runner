package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.service.CatalogDiff;
import com.linlay.agentplatform.util.StringHelpers;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

@Service
public class ToolFileRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolFileRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String CLASSPATH_PATTERN = "classpath*:/tools/*.{yml,yaml}";

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final ResourcePatternResolver resourcePatternResolver;
    private final Path legacyToolsDir;

    private final Object reloadLock = new Object();
    private volatile Map<String, ToolDescriptor> byName = Map.of();

    @Autowired
    public ToolFileRegistryService(ObjectMapper objectMapper) {
        this(objectMapper, new PathMatchingResourcePatternResolver(), null);
    }

    ToolFileRegistryService(ObjectMapper objectMapper, ResourcePatternResolver resourcePatternResolver) {
        this(objectMapper, resourcePatternResolver, null);
    }

    ToolFileRegistryService(ObjectMapper objectMapper, ResourcePatternResolver resourcePatternResolver, Path legacyToolsDir) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.resourcePatternResolver = resourcePatternResolver;
        this.legacyToolsDir = legacyToolsDir;
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
            if (legacyToolsDir != null) {
                loaded.putAll(loadToolsDirectory(legacyToolsDir));
            } else {
                for (Resource resource : classpathResources()) {
                    parseClasspathResource(resource, loaded, conflicts);
                }
            }
            byName = Map.copyOf(loaded);
            CatalogDiff diff = CatalogDiff.between(before, byName);
            log.debug("Refreshed classpath tool registry, size={}, changed={}", loaded.size(), diff.changedKeys().size());
            return diff;
        }
    }

    public Map<String, ToolDescriptor> loadToolsDirectory(Path dir) {
        Map<String, ToolDescriptor> loaded = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return Map.of();
        }
        for (Path file : YamlCatalogSupport.selectYamlFiles(YamlCatalogSupport.listRegularFiles(dir, log), "tool", log)) {
            parseRawTool(file.toString(), safeReadString(file), loaded, conflicts);
        }
        return loaded.isEmpty() ? Map.of() : Map.copyOf(loaded);
    }

    private List<Resource> classpathResources() {
        try {
            Resource[] resources = resourcePatternResolver.getResources(CLASSPATH_PATTERN);
            List<Resource> resolved = new ArrayList<>();
            for (Resource resource : resources) {
                if (resource != null && resource.exists() && resource.isReadable()) {
                    resolved.add(resource);
                }
            }
            resolved.sort(Comparator.comparing(this::resourceSortKey));
            return resolved;
        } catch (IOException ex) {
            log.warn("Cannot scan classpath tool resources with pattern {}", CLASSPATH_PATTERN, ex);
            return List.of();
        }
    }

    private void parseClasspathResource(
            Resource resource,
            Map<String, ToolDescriptor> loaded,
            Set<String> conflicts
    ) {
        String source = resourceDescription(resource);
        String raw;
        try {
            raw = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Skip invalid tool resource: {}", source, ex);
            return;
        }
        parseRawTool(source, raw, loaded, conflicts);
    }

    private String safeReadString(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            log.warn("Skip invalid tool file: {}", file, ex);
            return null;
        }
    }

    private void parseRawTool(
            String source,
            String raw,
            Map<String, ToolDescriptor> loaded,
            Set<String> conflicts
    ) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        JsonNode root;
        try {
            root = yamlMapper.readTree(raw);
        } catch (Exception ex) {
            log.warn("Skip invalid tool file: {}", source, ex);
            return;
        }

        if (!root.isObject()) {
            log.warn("Skip invalid tool file without object root: {}", source);
            return;
        }
        if (root.path("scaffold").asBoolean(false)) {
            log.debug("Skip scaffold tool placeholder: {}", source);
            return;
        }
        YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                raw,
                List.of("name", "label", "description", "type")
        );
        if (headerError.isPresent()) {
            log.warn("Skip tool '{}' due to invalid header: {} ({})", source, headerError.value(), source);
            return;
        }
        if (root.has("tools") && root.get("tools").isArray()) {
            log.warn("Skip legacy multi-tool file; tools[] is no longer supported: {}", source);
            return;
        }

        String type = normalize(root.path("type").asText(""));
        String name = normalize(root.path("name").asText(""));
        String normalizedName = normalizeName(name);
        if (normalizedName.isBlank()) {
            log.warn("Skip tool file without name: {}", source);
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
                log.warn("Skip non-function tool file: {}", source);
            } else {
                log.warn("Skip invalid tool file {}: {}", source, validationError);
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
                source.startsWith("classpath:") ? "local" : "agent-local",
                null,
                viewportKey,
                source
        );

        ToolDescriptor old = loaded.putIfAbsent(normalizedName, descriptor);
        if (old != null) {
            loaded.remove(normalizedName);
            conflicts.add(normalizedName);
            log.warn("Duplicate tool name '{}' found in {} and {}, both skipped", name, old.sourceFile(), source);
        }
    }

    private Map<String, Object> parseInputSchema(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
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

    private String resourceSortKey(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException ex) {
            return resourceDescription(resource);
        }
    }

    private String resourceDescription(Resource resource) {
        try {
            return "classpath:" + resource.getURL();
        } catch (IOException ex) {
            return String.valueOf(resource);
        }
    }
}
