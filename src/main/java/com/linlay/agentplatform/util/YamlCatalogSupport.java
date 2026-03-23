package com.linlay.agentplatform.util;

import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class YamlCatalogSupport {

    private static final List<String> YAML_EXTENSIONS = List.of(".yml", ".yaml");

    private YamlCatalogSupport() {
    }

    public static List<Path> selectYamlFiles(List<Path> candidates, String resourceLabel, Logger log) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, List<Path>> byBaseName = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            if (!isYamlFile(candidate)) {
                continue;
            }
            byBaseName.computeIfAbsent(fileBaseName(candidate), ignored -> new ArrayList<>()).add(candidate);
        }

        Comparator<Path> priorityComparator = Comparator
                .comparingInt(YamlCatalogSupport::formatPriority)
                .thenComparing(path -> path.getFileName().toString());
        List<Path> selected = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : byBaseName.entrySet()) {
            List<Path> files = new ArrayList<>(entry.getValue());
            files.sort(priorityComparator);
            Path chosen = files.getFirst();
            if (files.size() > 1 && log != null) {
                log.warn(
                        "Found multiple {} files for basename '{}', choose '{}' and ignore {}",
                        resourceLabel,
                        entry.getKey(),
                        chosen.getFileName(),
                        files.stream().skip(1).map(path -> path.getFileName().toString()).toList()
                );
            }
            selected.add(chosen);
        }
        selected.sort(priorityComparator);
        return List.copyOf(selected);
    }

    public static List<Path> listRegularFiles(Path dir, Logger log) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !containsHiddenPathSegment(dir, path))
                    .sorted(Comparator.comparing(path -> relativeSortKey(dir, path)))
                    .toList();
        } catch (IOException ex) {
            if (log != null) {
                log.warn("Cannot list files from {}", dir, ex);
            }
            return List.of();
        }
    }

    public static boolean isYamlFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return YAML_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    public static String fileBaseName(Path file) {
        String fileName = file.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        for (String extension : YAML_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return fileName.substring(0, fileName.length() - extension.length());
            }
        }
        if (lowerName.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - ".json".length());
        }
        return fileName;
    }

    public static HeaderError validateHeader(String raw, List<String> expectedKeys) {
        if (expectedKeys == null || expectedKeys.isEmpty()) {
            return HeaderError.empty();
        }
        String normalized = normalizeNewlines(raw);
        List<String> lines = normalized.lines().toList();
        if (lines.size() < expectedKeys.size()) {
            return HeaderError.of("first " + expectedKeys.size() + " lines must be " + expectedKeys);
        }

        for (int i = 0; i < expectedKeys.size(); i++) {
            String line = i == 0 ? stripBom(lines.get(i)) : lines.get(i);
            HeaderError error = validateHeaderLine(line, expectedKeys.get(i));
            if (error.isPresent()) {
                return error;
            }
        }
        return HeaderError.empty();
    }

    private static HeaderError validateHeaderLine(String line, String expectedKey) {
        if (line == null || line.isBlank()) {
            return HeaderError.of("line for '" + expectedKey + "' cannot be blank");
        }
        if (line.startsWith("#")) {
            return HeaderError.of("comments are not allowed before '" + expectedKey + "'");
        }
        if (Character.isWhitespace(line.charAt(0))) {
            return HeaderError.of("'" + expectedKey + "' must start at column 1");
        }
        int separator = line.indexOf(':');
        if (separator <= 0) {
            return HeaderError.of("line must start with '" + expectedKey + ":'");
        }
        String actualKey = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        if (!expectedKey.equals(actualKey)) {
            return HeaderError.of("expected '" + expectedKey + "' before any other field");
        }
        String value = line.substring(separator + 1).trim();
        if (!StringUtils.hasText(value) || value.startsWith("#")) {
            return HeaderError.of("'" + expectedKey + "' must have an inline value");
        }
        if (value.startsWith("|") || value.startsWith(">")) {
            return HeaderError.of("'" + expectedKey + "' does not support multi-line YAML scalars");
        }
        return HeaderError.empty();
    }

    private static boolean containsHiddenPathSegment(Path root, Path path) {
        if (root == null || path == null) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        Path relative = normalizedRoot.relativize(normalizedPath);
        for (Path segment : relative) {
            if (segment != null) {
                String text = segment.toString();
                if (StringUtils.hasText(text) && text.startsWith(".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String relativeSortKey(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return normalizedPath.toString();
        }
        return normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
    }

    private static int formatPriority(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".yml") ? 0 : 1;
    }

    private static String normalizeNewlines(String raw) {
        return raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripBom(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;
    }

    public record HeaderError(String value) {
        public static HeaderError empty() {
            return new HeaderError(null);
        }

        public static HeaderError of(String value) {
            return new HeaderError(value);
        }

        public boolean isPresent() {
            return StringUtils.hasText(value);
        }
    }
}
