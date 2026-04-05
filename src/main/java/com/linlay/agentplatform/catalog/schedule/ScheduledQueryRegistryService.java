package com.linlay.agentplatform.catalog.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.config.properties.ScheduleProperties;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.catalog.team.TeamDescriptor;
import com.linlay.agentplatform.catalog.team.TeamRegistryService;
import com.linlay.agentplatform.util.RuntimeCatalogNaming;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ScheduledQueryRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryRegistryService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SUPPORTED_SUFFIX = ".yml";
    private static final Set<String> SUPPORTED_QUERY_FIELDS = Set.of(
            "requestId",
            "chatId",
            "role",
            "message",
            "references",
            "params",
            "scene",
            "hidden"
    );
    private static final Set<String> DISALLOWED_QUERY_FIELDS = Set.of("agentKey", "teamId", "stream");

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final ScheduleProperties properties;
    private final TeamRegistryService teamRegistryService;

    private final Object reloadLock = new Object();
    private volatile Map<String, ScheduledQueryDescriptor> byId = Map.of();

    public ScheduledQueryRegistryService(
            ObjectMapper objectMapper,
            ScheduleProperties properties,
            TeamRegistryService teamRegistryService
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        this.teamRegistryService = teamRegistryService;
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
            YamlCatalogSupport.listRegularFiles(dir, log).stream()
                    .filter(RuntimeCatalogNaming::shouldLoadRuntimePath)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUPPORTED_SUFFIX))
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
            byId = Map.copyOf(loaded);
            log.debug("Refreshed schedule registry, size={}", loaded.size());
        }
    }

    private Optional<ScheduledQueryDescriptor> tryLoad(Path file) {
        String fileName = file.getFileName().toString();
        String fileBasedId = RuntimeCatalogNaming.logicalBaseName(fileName).trim();
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

        String agentKey = readRequiredText(root, "agentKey");
        if (!StringUtils.hasText(agentKey)) {
            log.warn("Skip schedule '{}' without agentKey: {}", scheduleId, file);
            return Optional.empty();
        }

        String teamId = normalizeNullable(root.path("teamId").asText(null));
        if (StringUtils.hasText(teamId)) {
            TeamDescriptor team = teamRegistryService.find(teamId).orElse(null);
            if (team == null) {
                log.warn("Skip schedule '{}' with missing teamId '{}': {}", scheduleId, teamId, file);
                return Optional.empty();
            }
            boolean belongsToTeam = team.agentKeys().stream()
                    .anyMatch(agent -> agentKey.equals(agent));
            if (!belongsToTeam) {
                log.warn(
                        "Skip schedule '{}' because agentKey '{}' is not in team '{}': {}",
                        scheduleId,
                        agentKey,
                        team.id(),
                        file
                );
                return Optional.empty();
            }
        }

        JsonNode environmentNode = root.get("environment");
        if (environmentNode != null && !environmentNode.isObject()) {
            log.warn("Skip schedule '{}' with non-object environment: {}", scheduleId, file);
            return Optional.empty();
        }
        String zoneId = environmentNode == null ? null : normalizeNullable(environmentNode.path("zoneId").asText(null));
        if (StringUtils.hasText(zoneId)) {
            try {
                java.time.ZoneId.of(zoneId);
            } catch (Exception ex) {
                log.warn("Skip schedule '{}' with invalid environment.zoneId '{}': {}", scheduleId, zoneId, file);
                return Optional.empty();
            }
        }

        JsonNode queryNode = root.get("query");
        if (queryNode == null || queryNode.isNull()) {
            log.warn("Skip schedule '{}' without query object: {}", scheduleId, file);
            return Optional.empty();
        }
        if (!queryNode.isObject()) {
            log.warn("Skip schedule '{}' with non-object query: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<String> queryFieldError = validateQueryFields(queryNode);
        if (queryFieldError.isPresent()) {
            log.warn("Skip schedule '{}' with invalid query fields: {} ({})", scheduleId, queryFieldError.get(), file);
            return Optional.empty();
        }
        String queryMessage = readRequiredText(queryNode, "message");
        if (!StringUtils.hasText(queryMessage)) {
            log.warn("Skip schedule '{}' without query.message: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<String> queryRequestId = readOptionalText(queryNode, "requestId");
        if (queryRequestId.isEmpty() && queryNode.has("requestId") && !queryNode.get("requestId").isNull()) {
            log.warn("Skip schedule '{}' with invalid query.requestId: {}", scheduleId, file);
            return Optional.empty();
        }
        String queryChatId = normalizeNullable(queryNode.path("chatId").asText(null));
        if (StringUtils.hasText(queryChatId)) {
            try {
                queryChatId = UUID.fromString(queryChatId).toString();
            } catch (IllegalArgumentException ex) {
                log.warn("Skip schedule '{}' with invalid query.chatId '{}': {}", scheduleId, queryChatId, file);
                return Optional.empty();
            }
        }
        Optional<String> queryRole = readOptionalText(queryNode, "role");
        if (queryRole.isEmpty() && queryNode.has("role") && !queryNode.get("role").isNull()) {
            log.warn("Skip schedule '{}' with invalid query.role: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<List<QueryRequest.Reference>> references = parseReferences(queryNode.get("references"));
        if (references.isEmpty()) {
            log.warn("Skip schedule '{}' with invalid query.references: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<Map<String, Object>> params = parseParams(queryNode.get("params"));
        if (params.isEmpty()) {
            log.warn("Skip schedule '{}' with invalid query.params: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<QueryRequest.Scene> scene = queryNode.has("scene")
                ? parseScene(queryNode.get("scene"))
                : Optional.of(new QueryRequest.Scene(null, null));
        if (scene.isEmpty()) {
            log.warn("Skip schedule '{}' with invalid query.scene: {}", scheduleId, file);
            return Optional.empty();
        }
        Optional<Boolean> hidden = queryNode.has("hidden")
                ? parseHidden(queryNode.get("hidden"))
                : Optional.of(Boolean.FALSE);
        if (hidden.isEmpty()) {
            log.warn("Skip schedule '{}' with invalid query.hidden: {}", scheduleId, file);
            return Optional.empty();
        }

        boolean enabled = !root.has("enabled") || root.path("enabled").asBoolean(true);

        String pushUrl = normalizeNullable(root.path("pushUrl").asText(null));
        String pushTargetId = normalizeNullable(root.path("pushTargetId").asText(null));

        return Optional.of(new ScheduledQueryDescriptor(
                scheduleId,
                name,
                description,
                enabled,
                cron,
                agentKey,
                teamId,
                new ScheduledQueryDescriptor.Environment(zoneId),
                new ScheduledQueryDescriptor.Query(
                        queryRequestId.orElse(null),
                        queryChatId,
                        queryRole.orElse(null),
                        queryMessage,
                        references.orElse(List.of()),
                        params.orElse(Map.of()),
                        normalizeScene(scene.orElse(null)),
                        Boolean.TRUE.equals(hidden.orElse(Boolean.FALSE)) ? Boolean.TRUE : null
                ),
                pushUrl,
                pushTargetId,
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

    private Optional<Map<String, Object>> parseParams(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.of(Map.of());
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> converted = objectMapper.convertValue(node, MAP_TYPE);
            if (converted == null || converted.isEmpty()) {
                return Optional.of(Map.of());
            }
            return Optional.of(Map.copyOf(new LinkedHashMap<>(converted)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<List<QueryRequest.Reference>> parseReferences(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.of(List.of());
        }
        if (!node.isArray()) {
            return Optional.empty();
        }
        try {
            List<QueryRequest.Reference> converted = objectMapper.convertValue(
                    node,
                    new TypeReference<List<QueryRequest.Reference>>() {
                    }
            );
            return Optional.of(converted == null ? List.of() : List.copyOf(converted));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<QueryRequest.Scene> parseScene(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.ofNullable(null);
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.convertValue(node, QueryRequest.Scene.class));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> parseHidden(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.ofNullable(null);
        }
        if (!node.isBoolean()) {
            return Optional.empty();
        }
        return Optional.of(node.booleanValue());
    }

    private Optional<String> validateQueryFields(JsonNode queryNode) {
        if (queryNode == null || !queryNode.isObject()) {
            return Optional.of("query must be an object");
        }
        var names = queryNode.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (DISALLOWED_QUERY_FIELDS.contains(field)) {
                if ("stream".equals(field)) {
                    return Optional.of("query.stream is not supported");
                }
                return Optional.of("query." + field + " must not be provided; use the schedule top-level field instead");
            }
            if (!SUPPORTED_QUERY_FIELDS.contains(field)) {
                return Optional.of("unsupported query field: " + field);
            }
        }
        return Optional.empty();
    }

    private Optional<String> readOptionalText(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || !root.has(fieldName)) {
            return Optional.ofNullable(null);
        }
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return Optional.ofNullable(null);
        }
        if (!value.isTextual()) {
            return Optional.empty();
        }
        return Optional.ofNullable(normalizeNullable(value.asText(null)));
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

    private QueryRequest.Scene normalizeScene(QueryRequest.Scene scene) {
        if (scene == null) {
            return null;
        }
        String url = normalizeNullable(scene.url());
        String title = normalizeNullable(scene.title());
        if (url == null && title == null) {
            return null;
        }
        return new QueryRequest.Scene(url, title);
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
