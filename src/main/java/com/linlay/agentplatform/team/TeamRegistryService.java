package com.linlay.agentplatform.team;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@DependsOn("runtimeResourceSyncService")
public class TeamRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistryService.class);
    private static final Pattern TEAM_ID_PATTERN = Pattern.compile("^[0-9a-f]{12}$");

    private final ObjectMapper objectMapper;
    private final TeamCatalogProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, TeamDescriptor> byId = Map.of();

    public TeamRegistryService(ObjectMapper objectMapper, TeamCatalogProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        refreshTeams();
    }

    public List<TeamDescriptor> list() {
        return byId.values().stream()
                .sorted(Comparator.comparing(TeamDescriptor::id))
                .toList();
    }

    public Optional<TeamDescriptor> find(String teamId) {
        if (!StringUtils.hasText(teamId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(normalizeTeamId(teamId)));
    }

    public void refreshTeams() {
        synchronized (reloadLock) {
            Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
            Map<String, TeamDescriptor> loaded = new LinkedHashMap<>();
            if (!Files.exists(dir)) {
                byId = Map.of();
                return;
            }
            if (!Files.isDirectory(dir)) {
                log.warn("Configured teams directory is not a directory: {}", dir);
                byId = Map.of();
                return;
            }

            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> tryLoad(path).ifPresent(team -> {
                            if (loaded.containsKey(team.id())) {
                                log.warn("Duplicate team id '{}' found in {}, keep the first one", team.id(), path);
                                return;
                            }
                            loaded.put(team.id(), team);
                        }));
            } catch (IOException ex) {
                log.warn("Cannot list team files from {}", dir, ex);
            }

            byId = Map.copyOf(loaded);
            log.debug("Refreshed team registry, size={}", loaded.size());
        }
    }

    private Optional<TeamDescriptor> tryLoad(Path file) {
        String fileName = file.getFileName().toString();
        String fileBasedId = fileName.substring(0, fileName.length() - ".json".length()).trim();
        String teamId = normalizeTeamId(fileBasedId);
        if (!isValidTeamId(teamId)) {
            log.warn("Skip team file with invalid teamId '{}': {}", fileBasedId, file);
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            String name = root.path("name").asText("").trim();
            if (name.isBlank()) {
                log.warn("Skip team '{}' without non-blank name: {}", teamId, file);
                return Optional.empty();
            }
            List<String> agentKeys = parseAgentKeys(root.path("agentKeys"));
            String defaultAgentKey = parseDefaultAgentKey(root.path("defaultAgentKey"));
            return Optional.of(new TeamDescriptor(teamId, name, agentKeys, defaultAgentKey, file.toString()));
        } catch (Exception ex) {
            log.warn("Skip invalid team file: {}", file, ex);
            return Optional.empty();
        }
    }

    private List<String> parseAgentKeys(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (value == null || !value.isTextual()) {
                continue;
            }
            String normalized = value.asText("").trim();
            if (normalized.isBlank()) {
                continue;
            }
            unique.add(normalized);
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private String parseDefaultAgentKey(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String normalized = node.asText("").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeTeamId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidTeamId(String teamId) {
        return TEAM_ID_PATTERN.matcher(teamId).matches();
    }
}
