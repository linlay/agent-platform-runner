package com.linlay.agentplatform.config.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.linlay.agentplatform.util.RuntimeCatalogNaming;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ConfigDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, String> PREFIX_MAP = Map.of(
            "bash", "agent.tools.bash",
            "container-hub", "agent.tools.container-hub",
            "cors", "agent.cors",
            "auth", "agent.auth",
            "chat-image-token", "agent.chat-image-token",
            "voice-tts", "agent.tools.voice-tts"
    );
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path configDir = ConfigDirectorySupport.resolveConfigDirectory().orElse(null);
        if (configDir == null || !Files.isDirectory(configDir)) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        String anchor = resolveAnchor(propertySources);

        for (Path file : resolveTopLevelConfigFiles(configDir)) {
            anchor = loadYamlFile(propertySources, anchor, file);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private List<Path> resolveTopLevelConfigFiles(Path configDir) {
        if (configDir == null || !Files.isDirectory(configDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(configDir)) {
            Map<String, List<Path>> candidatesByBase = new LinkedHashMap<>();
            stream.filter(Files::isRegularFile)
                    .filter(this::isRuntimeYamlFile)
                    .forEach(path -> {
                        String logicalBaseName = RuntimeCatalogNaming.logicalBaseName(path.getFileName().toString());
                        if (PREFIX_MAP.containsKey(logicalBaseName)) {
                            candidatesByBase.computeIfAbsent(logicalBaseName, ignored -> new ArrayList<>()).add(path);
                        }
                    });

            List<Path> resolved = new ArrayList<>();
            Comparator<Path> comparator = Comparator
                    .comparingInt(this::runtimeVariantPriority)
                    .thenComparingInt(this::yamlFormatPriority)
                    .thenComparing(path -> path.getFileName().toString());
            for (String baseName : List.of("auth", "container-hub", "bash", "voice-tts", "cors", "chat-image-token")) {
                List<Path> candidates = candidatesByBase.get(baseName);
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                resolved.add(candidates.stream().sorted(comparator).findFirst().orElseThrow());
            }
            return List.copyOf(resolved);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list config directory: " + configDir, ex);
        }
    }

    private boolean isRuntimeYamlFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName().toString();
        if (!RuntimeCatalogNaming.shouldLoadRuntimeName(name)) {
            return false;
        }
        String lowerName = name.toLowerCase();
        return lowerName.endsWith(".yml") || lowerName.endsWith(".yaml");
    }

    private int runtimeVariantPriority(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return RuntimeCatalogNaming.isDemoName(name) ? 1 : 0;
    }

    private int yamlFormatPriority(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        if (name.endsWith(".yml")) {
            return 0;
        }
        if (name.endsWith(".yaml")) {
            return 1;
        }
        return 2;
    }

    private String loadYamlFile(MutablePropertySources propertySources, String anchor, Path file) {
        String baseName = baseName(file);
        String prefix = PREFIX_MAP.get(baseName);
        if (prefix == null) {
            return anchor;
        }
        Map<String, Object> loaded = loadYamlProperties(file);
        if (loaded.isEmpty()) {
            return anchor;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : loaded.entrySet()) {
            prefixed.put(prefix + "." + entry.getKey(), entry.getValue());
        }
        MapPropertySource propertySource = new MapPropertySource("config:" + file.toAbsolutePath(), prefixed);
        if (anchor != null && propertySources.contains(anchor)) {
            propertySources.addAfter(anchor, propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }
        return propertySource.getName();
    }

    private Map<String, Object> loadYamlProperties(Path file) {
        JsonNode root;
        try {
            root = YAML_MAPPER.readTree(file.toFile());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read config file: " + file, ex);
        }
        if (root == null || root.isNull()) {
            return Map.of();
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Config file must contain a YAML object: " + file);
        }

        Map<String, Object> loaded = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            validateFlatKey(file, key, value);
            loaded.put(key, toPropertyValue(file, key, value));
        });
        return loaded;
    }

    private void validateFlatKey(Path file, String key, JsonNode value) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Config file contains a blank key: " + file);
        }
        if ("container-hub".equals(baseName(file)) && key.startsWith("mounts.")) {
            throw new IllegalStateException("Config file must not use deprecated key '" + key + "': " + file
                    + ". Move shared mount directories to runner-level config"
                    + " (ROOT_DIR, PAN_DIR, AGENTS_DIR, REGISTRIES_DIR, TEAMS_DIR, SCHEDULES_DIR,"
                    + " SKILLS_MARKET_DIR, CHATS_DIR, OWNER_DIR).");
        }
        if (key.startsWith("agent.")) {
            throw new IllegalStateException("Config file must use flat keys only: " + file + " contains deprecated key '" + key + "'");
        }
        if (value != null && value.isObject()) {
            throw new IllegalStateException("Config file must use flat keys only: " + file + " contains nested object key '" + key + "'");
        }
    }

    private Object toPropertyValue(Path file, String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.doubleValue();
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : value) {
                if (item != null && item.isObject()) {
                    throw new IllegalStateException("Config file must use flat scalar/list values only: " + file + " contains nested object item under '" + key + "'");
                }
                items.add(toPropertyValue(file, key, item));
            }
            return List.copyOf(items);
        }
        return value.asText();
    }

    private String baseName(Path file) {
        String fileName = file.getFileName().toString();
        return RuntimeCatalogNaming.logicalBaseName(fileName);
    }

    private String resolveAnchor(MutablePropertySources propertySources) {
        if (propertySources.contains("systemEnvironment")) {
            return "systemEnvironment";
        }
        if (propertySources.contains("systemProperties")) {
            return "systemProperties";
        }
        if (propertySources.contains("defaultProperties")) {
            return "defaultProperties";
        }
        MapPropertySource bootstrap = new MapPropertySource("config-directory-bootstrap", java.util.Map.of());
        propertySources.addFirst(bootstrap);
        return bootstrap.getName();
    }
}
