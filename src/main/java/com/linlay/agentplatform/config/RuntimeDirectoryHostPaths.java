package com.linlay.agentplatform.config;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RuntimeDirectoryHostPaths {

    public static final String HOST_DIRS_FILE_ENV = "SANDBOX_HOST_DIRS_FILE";
    public static final String DEFAULT_HOST_DIRS_FILE = "/tmp/runner-host.env";
    public static final String SYSTEM_ENVIRONMENT_SOURCE = "system environment";

    private static final Set<String> RUNTIME_DIR_KEYS = Set.of(
            "AGENTS_DIR",
            "OWNER_DIR",
            "TEAMS_DIR",
            "REGISTRIES_DIR",
            "SKILLS_MARKET_DIR",
            "SCHEDULES_DIR",
            "CHATS_DIR",
            "ROOT_DIR",
            "PAN_DIR"
    );

    private final Map<String, String> values;
    private final String sourcePath;

    public RuntimeDirectoryHostPaths(Map<String, String> values) {
        this(values, DEFAULT_HOST_DIRS_FILE);
    }

    public RuntimeDirectoryHostPaths(Map<String, String> values, String sourcePath) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
        this.sourcePath = StringUtils.hasText(sourcePath) ? sourcePath.trim() : DEFAULT_HOST_DIRS_FILE;
    }

    public static RuntimeDirectoryHostPaths load(Map<String, String> environment) {
        Map<String, String> fromEnvironment = readFromEnvironment(environment);
        if (!fromEnvironment.isEmpty()) {
            return new RuntimeDirectoryHostPaths(fromEnvironment, SYSTEM_ENVIRONMENT_SOURCE);
        }
        return loadFromFile(environment);
    }

    private static Map<String, String> readFromEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String key : RUNTIME_DIR_KEYS) {
            String value = environment.get(key);
            if (StringUtils.hasText(value)) {
                values.put(key, value.trim());
            }
        }
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private static RuntimeDirectoryHostPaths loadFromFile(Map<String, String> environment) {
        String sourcePath = DEFAULT_HOST_DIRS_FILE;
        if (environment != null) {
            String configured = environment.get(HOST_DIRS_FILE_ENV);
            if (StringUtils.hasText(configured)) {
                sourcePath = configured.trim();
            }
        }
        return new RuntimeDirectoryHostPaths(loadFrom(Path.of(sourcePath)), sourcePath);
    }

    public String get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String value = values.get(key.trim());
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public String sourcePath() {
        return sourcePath;
    }

    static Map<String, String> loadFrom(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(values, line);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read runtime directory host paths from " + file, ex);
        }
        return Map.copyOf(values);
    }

    private static void parseLine(Map<String, String> values, String rawLine) {
        if (rawLine == null) {
            return;
        }
        String line = stripBom(rawLine).trim();
        if (!StringUtils.hasText(line) || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).trim();
        }
        int equals = line.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = line.substring(0, equals).trim();
        if (!RUNTIME_DIR_KEYS.contains(key)) {
            return;
        }
        String value = stripMatchingQuotes(line.substring(equals + 1).trim());
        if (StringUtils.hasText(value)) {
            values.put(key, value.trim());
        }
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static String stripMatchingQuotes(String value) {
        if (!StringUtils.hasText(value) || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
