package com.linlay.agentplatform.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class ConfigDirectorySupport {

    public static final String CONFIG_DIR_ENV = "AGENT_CONFIG_DIR";
    private static final Path LOCAL_CONFIG_DIR = Path.of("configs").toAbsolutePath().normalize();
    private static final Path CONTAINER_CONFIG_DIR = Path.of("/opt/configs").toAbsolutePath().normalize();

    private ConfigDirectorySupport() {
    }

    public static Optional<Path> resolveConfigDirectory(ConfigurableEnvironment environment) {
        String configured = environment == null ? null : environment.getProperty(CONFIG_DIR_ENV);
        return resolveConfigDirectory(configured);
    }

    public static Optional<Path> resolveConfigDirectory(String configured) {
        if (StringUtils.hasText(configured)) {
            return Optional.of(Path.of(configured.trim()).toAbsolutePath().normalize());
        }
        if (java.nio.file.Files.isDirectory(LOCAL_CONFIG_DIR)) {
            return Optional.of(LOCAL_CONFIG_DIR);
        }
        if (java.nio.file.Files.isDirectory(CONTAINER_CONFIG_DIR)) {
            return Optional.of(CONTAINER_CONFIG_DIR);
        }
        return Optional.of(LOCAL_CONFIG_DIR);
    }

    public static Path resolveConfigRelativePath(String configured, String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw new IllegalStateException("Config path must not be blank");
        }
        Path path = Path.of(rawPath.trim());
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return resolveConfigDirectory(configured)
                .orElse(LOCAL_CONFIG_DIR)
                .resolve(path)
                .toAbsolutePath()
                .normalize();
    }

    public static Set<Path> candidateConfigDirectories(String configured) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(configured)) {
            candidates.add(Path.of(configured.trim()).toAbsolutePath().normalize());
        }
        candidates.add(LOCAL_CONFIG_DIR);
        candidates.add(CONTAINER_CONFIG_DIR);
        return candidates;
    }
}
