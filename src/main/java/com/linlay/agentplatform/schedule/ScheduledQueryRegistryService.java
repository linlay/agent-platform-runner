package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
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

import org.springframework.scheduling.support.CronExpression;

@Service
@DependsOn("runtimeResourceSyncService")
public class ScheduledQueryRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final ScheduleCatalogProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, ScheduledQueryDescriptor> byId = Map.of();

    public ScheduledQueryRegistryService(
            ObjectMapper objectMapper,
            ScheduleCatalogProperties properties
    ) {
        this.objectMapper = objectMapper;
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
                stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
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
        String fileBasedId = fileName.substring(0, fileName.length() - ".json".length()).trim();
        String scheduleId = normalizeId(fileBasedId);
        if (!StringUtils.hasText(scheduleId)) {
            log.warn("Skip schedule file with empty id: {}", file);
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(file));
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

        String query = normalize(root.path("query").asText(""));
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

        String name = normalize(root.path("name").asText(""));
        boolean enabled = !root.has("enabled") || root.path("enabled").asBoolean(true);
        Map<String, Object> params = parseParams(root.get("params"));

        return Optional.of(new ScheduledQueryDescriptor(
                scheduleId,
                StringUtils.hasText(name) ? name : scheduleId,
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
}
