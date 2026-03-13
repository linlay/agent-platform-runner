package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@DependsOn("runtimeResourceSyncService")
public class ScheduledQueryRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SUPPORTED_SUFFIX = ".yml";

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final ScheduleProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, ScheduledQueryDescriptor> byId = Map.of();

    public ScheduledQueryRegistryService(
            ObjectMapper objectMapper,
            ScheduleProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        refreshSchedules();
    }

    public List<ScheduledQueryDescriptor> list() {
        return byId.values().stream()
                .sorted(Comparator.comparing(ScheduledQueryDescriptor::id))
                .toList();
    }

    public Map<String, ScheduledQueryDescriptor> snapshot() {
        return byId;
    }

    public Optional<ScheduledQueryDescriptor> find(String scheduleId) {
        if (!StringUtils.hasText(scheduleId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(normalizeId(scheduleId)));
    }

    public void refreshSchedules() {
        synchronized (reloadLock) {
            Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
            if (!Files.exists(dir)) {
                byId = Map.of();
                return;
            }
            if (!Files.isDirectory(dir)) {
                log.warn("Configured schedules directory is not a directory: {}", dir);
                byId = Map.of();
                return;
            }

            Map<String, ScheduledQueryDescriptor> loaded = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUPPORTED_SUFFIX))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> tryLoad(path).ifPresent(descriptor -> {
                            ScheduledQueryDescriptor existing = loaded.putIfAbsent(descriptor.id(), descriptor);
                            if (existing != null) {
                                log.warn(
                                        "Duplicate schedule id '{}' found in {} and {}, keep the first one",
                                        descriptor.id(),
                                        existing.sourceFile(),
                                        descriptor.sourceFile()
                                );
                            }
                        }));
            } catch (IOException ex) {
                log.warn("Cannot list schedule files from {}", dir, ex);
            }
            byId = Map.copyOf(loaded);
            log.debug("Refreshed schedule registry, size={}", loaded.size());
        }
    }

    private Optional<ScheduledQueryDescriptor> tryLoad(Path file) {
        String fileName = file.getFileName().toString();
        String fileBasedId = fileName.substring(0, fileName.length() - SUPPORTED_SUFFIX.length()).trim();
        String scheduleId = normalizeId(fileBasedId);
        if (!StringUtils.hasText(scheduleId)) {
            log.warn("Skip schedule file with empty id: {}", file);
            return Optional.empty();
        }

        String raw;
        try {
            raw = Files.readString(file);
        } catch (Exception ex) {
            log.warn("Skip unreadable schedule file: {}", file, ex);
            return Optional.empty();
        }

        Optional<String> headerError = validateHeader(raw);
        if (headerError.isPresent()) {
            log.warn("Skip schedule '{}' due to invalid header: {} ({})", scheduleId, headerError.get(), file);
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = yamlMapper.readTree(raw);
        } catch (Exception ex) {
            log.warn("Skip invalid schedule file: {}", file, ex);
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            log.warn("Skip schedule file with non-object payload: {}", file);
            return Optional.empty();
        }

        String cron = normalize(root.path("cron").asText(""));
        if (!StringUtils.hasText(cron)) {
            log.warn("Skip schedule '{}' without cron: {}", scheduleId, file);
            return Optional.empty();
        }
        if (!isValidCron(cron)) {
            log.warn("Skip schedule '{}' with invalid cron '{}': {}", scheduleId, cron, file);
            return Optional.empty();
        }

        String name = readRequiredText(root, "name");
        if (!StringUtils.hasText(name)) {
            log.warn("Skip schedule '{}' without name: {}", scheduleId, file);
            return Optional.empty();
        }

        String description = readRequiredText(root, "description");
        if (!StringUtils.hasText(description)) {
            log.warn("Skip schedule '{}' without description: {}", scheduleId, file);
            return Optional.empty();
        }

        String query = readRequiredText(root, "query");
        if (!StringUtils.hasText(query)) {
            log.warn("Skip schedule '{}' without query: {}", scheduleId, file);
            return Optional.empty();
        }

        String agentKey = normalizeNullable(root.path("agentKey").asText(null));
        String teamId = normalizeNullable(root.path("teamId").asText(null));
        if (!StringUtils.hasText(agentKey) && !StringUtils.hasText(teamId)) {
            log.warn("Skip schedule '{}' without agentKey/teamId: {}", scheduleId, file);
            return Optional.empty();
        }

        String zoneId = normalizeNullable(root.path("zoneId").asText(null));
        if (StringUtils.hasText(zoneId)) {
            try {
                java.time.ZoneId.of(zoneId);
            } catch (Exception ex) {
                log.warn("Skip schedule '{}' with invalid zoneId '{}': {}", scheduleId, zoneId, file);
                return Optional.empty();
            }
        }

        boolean enabled = !root.has("enabled") || root.path("enabled").asBoolean(true);
        Map<String, Object> params = parseParams(root.get("params"));

        return Optional.of(new ScheduledQueryDescriptor(
                scheduleId,
                name,
                description,
                enabled,
                cron,
                zoneId,
                agentKey,
                teamId,
                query,
                params,
                file.toString()
        ));
    }

    private boolean isValidCron(String expression) {
        try {
            CronExpression.parse(expression);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Optional<String> validateHeader(String raw) {
        String normalized = normalizeNewlines(raw);
        List<String> lines = normalized.lines().toList();
        if (lines.size() < 2) {
            return Optional.of("first two lines must be name and description");
        }

        Optional<String> nameError = validateHeaderLine(stripBom(lines.get(0)), "name", false);
        if (nameError.isPresent()) {
            return nameError;
        }
        return validateHeaderLine(lines.get(1), "description", true);
    }

    private Optional<String> validateHeaderLine(String line, String expectedKey, boolean singleLineOnly) {
        if (line == null || line.isBlank()) {
            return Optional.of("line for '" + expectedKey + "' cannot be blank");
        }
        if (line.startsWith("#")) {
            return Optional.of("comments are not allowed before '" + expectedKey + "'");
        }
        if (Character.isWhitespace(line.charAt(0))) {
            return Optional.of("'" + expectedKey + "' must start at column 1");
        }

        int separator = line.indexOf(':');
        if (separator <= 0) {
            return Optional.of("line must start with '" + expectedKey + ":'");
        }
        String actualKey = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        if (!expectedKey.equals(actualKey)) {
            return Optional.of("expected '" + expectedKey + "' before any other field");
        }

        String value = line.substring(separator + 1).trim();
        if (!StringUtils.hasText(value) || value.startsWith("#")) {
            return Optional.of("'" + expectedKey + "' must have an inline value");
        }
        if (singleLineOnly && (value.startsWith("|") || value.startsWith(">"))) {
            return Optional.of("'" + expectedKey + "' does not support multi-line YAML scalars");
        }
        return Optional.empty();
    }

    private Map<String, Object> parseParams(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of();
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
            if (converted == null || converted.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(new LinkedHashMap<>(converted));
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private String readRequiredText(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || !root.has(fieldName)) {
            return "";
        }
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        return normalize(value.asText());
    }

    private String normalizeId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private String normalizeNullable(String raw) {
        String normalized = normalize(raw);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizeNewlines(String raw) {
        return raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String stripBom(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;
    }
}
