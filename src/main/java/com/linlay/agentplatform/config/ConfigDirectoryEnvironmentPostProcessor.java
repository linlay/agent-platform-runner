package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final List<String> TOP_LEVEL_FILES = List.of(
            "auth.yml",
            "container-hub.yml",
            "bash.yml",
            "voice-tts.yml",
            "cors.yml",
            "chat-image-token.yml"
    );

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
        RuntimeDirectoryEnvironmentSupport.validateNoUnsupportedDirectoryVariables(environment);
        RuntimeDirectoryEnvironmentSupport.validateNoDeprecatedDirectoryVariables(environment);
        RuntimeDirectoryEnvironmentSupport.validateNoDeprecatedProperties(environment);
        Path configDir = ConfigDirectorySupport.resolveConfigDirectory().orElse(null);
        if (configDir == null || !Files.isDirectory(configDir)) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        String anchor = resolveAnchor(propertySources);

        for (String fileName : TOP_LEVEL_FILES) {
            Path file = configDir.resolve(fileName);
            if (!isRuntimeYamlFile(file)) {
                continue;
            }
            anchor = loadYamlFile(propertySources, anchor, file);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean isRuntimeYamlFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase();
        if (name.contains(".example.")) {
            return false;
        }
        return name.endsWith(".yml") || name.endsWith(".yaml");
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
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
