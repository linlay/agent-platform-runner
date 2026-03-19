package com.linlay.agentplatform.config;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class ConfigDirectorySupport {

    public static final String CONFIG_DIR_ENV = "CONFIGS_DIR";
    private static final Path CONTAINER_CONFIG_DIR = Path.of("/opt/configs").toAbsolutePath().normalize();

    private ConfigDirectorySupport() {
    }

    public static Optional<Path> resolveConfigDirectory() {
        Path localConfigDir = localConfigDirectory();
        if (Files.isDirectory(localConfigDir)) {
            return Optional.of(localConfigDir);
        }
        if (Files.isDirectory(CONTAINER_CONFIG_DIR)) {
            return Optional.of(CONTAINER_CONFIG_DIR);
        }
        return Optional.of(localConfigDir);
    }

    public static Path resolveConfigRelativePath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw new IllegalStateException("Config path must not be blank");
        }
        Path path = Path.of(rawPath.trim());
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return resolveConfigDirectory()
                .orElse(localConfigDirectory())
                .resolve(path)
                .toAbsolutePath()
                .normalize();
    }

    public static Set<Path> candidateConfigDirectories() {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(localConfigDirectory());
        candidates.add(CONTAINER_CONFIG_DIR);
        return Set.copyOf(candidates);
    }

    private static Path localConfigDirectory() {
        return Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath()
                .normalize()
                .resolve("configs")
                .normalize();
    }
}
