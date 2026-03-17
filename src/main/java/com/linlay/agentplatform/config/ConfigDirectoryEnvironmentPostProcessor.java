package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class ConfigDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final List<String> TOP_LEVEL_FILES = List.of(
            "auth.yml",
            "agentbox.yml",
            "bash.yml",
            "voice-tts.yml",
            "cors.yml",
            "chat-image-token.yml"
    );
    private static final String PROVIDERS_DIR = "providers";

    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path configDir = ConfigDirectorySupport.resolveConfigDirectory(environment).orElse(null);
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

        Path providersDir = configDir.resolve(PROVIDERS_DIR);
        if (!Files.isDirectory(providersDir)) {
            return;
        }

        Set<String> providerKeys = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(providersDir)) {
            for (Path file : stream.filter(this::isRuntimeYamlFile).sorted().toList()) {
                validateProviderFile(file, providerKeys);
                anchor = loadYamlFile(propertySources, anchor, file);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to scan provider config directory: " + providersDir, ex);
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

    private void validateProviderFile(Path file, Set<String> providerKeys) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(Files.readString(file));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse provider config file: " + file, ex);
        }
        JsonNode providers = root.path("agent").path("providers");
        if (!providers.isObject()) {
            throw new IllegalStateException("Provider config file must define agent.providers.<providerKey>: " + file);
        }

        List<String> keys = new ArrayList<>();
        Iterator<String> fieldNames = providers.fieldNames();
        while (fieldNames.hasNext()) {
            keys.add(fieldNames.next());
        }
        if (keys.size() != 1 || !StringUtils.hasText(keys.getFirst())) {
            throw new IllegalStateException("Provider config file must define exactly one provider key: " + file);
        }

        String providerKey = keys.getFirst().trim().toLowerCase();
        if (!providerKeys.add(providerKey)) {
            throw new IllegalStateException("Duplicate provider key '" + providerKey + "' detected in configs/providers");
        }
    }

    private String loadYamlFile(MutablePropertySources propertySources, String anchor, Path file) {
        try {
            List<PropertySource<?>> loaded = yamlLoader.load("config:" + file.toAbsolutePath(), new FileSystemResource(file));
            String currentAnchor = anchor;
            for (PropertySource<?> propertySource : loaded) {
                if (currentAnchor != null && propertySources.contains(currentAnchor)) {
                    propertySources.addAfter(currentAnchor, propertySource);
                } else {
                    propertySources.addFirst(propertySource);
                }
                currentAnchor = propertySource.getName();
            }
            return currentAnchor;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load config file: " + file, ex);
        }
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
