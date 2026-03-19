package com.linlay.agentplatform.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ConfigDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final List<String> TOP_LEVEL_FILES = List.of(
            "auth.yml",
            "container-hub.yml",
            "bash.yml",
            "voice-tts.yml",
            "cors.yml",
            "chat-image-token.yml"
    );
    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        RuntimeDirectoryEnvironmentSupport.validateNoDeprecatedDirectoryVariables(environment);
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
