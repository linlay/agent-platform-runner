package com.linlay.agentplatform.catalog.skill;

import com.linlay.agentplatform.config.properties.SkillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.linlay.agentplatform.util.CatalogDiff;
import com.linlay.agentplatform.util.RuntimeCatalogNaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class SkillRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistryService.class);
    private static final String SKILL_FILE = "SKILL.md";

    private final SkillProperties properties;

    private final Object reloadLock = new Object();
    private volatile Map<String, SkillDescriptor> byId = Map.of();

    public SkillRegistryService(
            SkillProperties properties
    ) {
        this.properties = properties;
        refreshSkills();
    }

    public List<SkillDescriptor> list() {
        return byId.values().stream()
                .sorted(Comparator.comparing(SkillDescriptor::id))
                .toList();
    }

    public Optional<SkillDescriptor> find(String skillId) {
        String normalized = normalizeSkillId(skillId);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(normalized));
    }

    public Path skillsRoot() {
        if (!StringUtils.hasText(properties.getExternalDir())) {
            return null;
        }
        return Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
    }

    public Optional<SkillDescriptor> findForAgent(String skillId, Path perAgentSkillsDir) {
        String normalized = normalizeSkillId(skillId);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        if (perAgentSkillsDir != null) {
            Optional<SkillDescriptor> local = loadSkill(perAgentSkillsDir, normalized);
            if (local.isPresent()) {
                return local;
            }
        }
        return find(normalized);
    }

    public CatalogDiff refreshSkills() {
        synchronized (reloadLock) {
            Map<String, SkillDescriptor> before = byId;
            Map<String, SkillDescriptor> loaded = new LinkedHashMap<>();
            Path dir = skillsRoot();
            if (dir == null) {
                byId = Map.of();
                return CatalogDiff.between(before, byId);
            }
            if (!Files.exists(dir)) {
                byId = Map.of();
                return CatalogDiff.between(before, byId);
            }
            if (!Files.isDirectory(dir)) {
                log.warn("Configured skills directory is not a directory: {}", dir);
                byId = Map.of();
                return CatalogDiff.between(before, byId);
            }

            try (Stream<Path> stream = Files.list(dir)) {
                stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            if (isHiddenEntry(path)) {
                                return;
                            }
                            if (!RuntimeCatalogNaming.shouldLoadRuntimePath(path)) {
                                return;
                            }
                            if (Files.isRegularFile(path)) {
                                log.warn(
                                        "Invalid skill layout entry '{}'. Skill files must be placed at skills/<skill-id>/{}",
                                        path,
                                        SKILL_FILE
                                );
                                return;
                            }
                            if (!Files.isDirectory(path)) {
                                return;
                            }
                            String skillId = normalizeSkillId(path.getFileName() == null ? "" : path.getFileName().toString());
                            loadSkill(dir, skillId).ifPresent(descriptor -> {
                                SkillDescriptor old = loaded.putIfAbsent(descriptor.id(), descriptor);
                                if (old != null) {
                                    log.warn(
                                            "Duplicate skill id '{}' found in {} and {}, keep the first one",
                                            descriptor.id(),
                                            old.sourceFile(),
                                            descriptor.sourceFile()
                                    );
                                }
                            });
                        });
            } catch (IOException ex) {
                log.warn("Cannot list skills from {}", dir, ex);
            }

            byId = Map.copyOf(loaded);
            CatalogDiff diff = CatalogDiff.between(before, byId);
            log.debug("Refreshed skill registry, size={}, changed={}", loaded.size(), diff.changedKeys().size());
            return diff;
        }
    }

    private Optional<SkillDescriptor> loadSkill(Path skillsRoot, String skillId) {
        String id = normalizeSkillId(skillId);
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }

        Path root = skillsRoot.toAbsolutePath().normalize();
        Path skillDir = root.resolve(id).normalize();
        if (!skillDir.startsWith(root)) {
            return Optional.empty();
        }
        if (isHiddenEntry(skillDir)) {
            return Optional.empty();
        }
        if (!RuntimeCatalogNaming.shouldLoadRuntimePath(skillDir)) {
            return Optional.empty();
        }
        Path skillFile = skillDir.resolve(SKILL_FILE).normalize();
        if (!skillFile.startsWith(skillDir) || !Files.isRegularFile(skillFile)) {
            log.debug("Skip skill '{}' without {}", id, SKILL_FILE);
            return Optional.empty();
        }

        try {
            ParsedSkill parsed = parseSkillMarkdown(Files.readString(skillFile));
            if (parsed.scaffold()) {
                log.debug("Skip scaffold skill '{}': {}", id, skillFile);
                return Optional.empty();
            }
            String name = StringUtils.hasText(parsed.name) ? parsed.name : id;
            String description = StringUtils.hasText(parsed.description) ? parsed.description : "";
            PromptSlice promptSlice = slicePrompt(parsed.body);
            return Optional.of(new SkillDescriptor(
                    id,
                    name,
                    description,
                    promptSlice.prompt,
                    skillFile.toString(),
                    promptSlice.truncated
            ));
        } catch (Exception ex) {
            log.warn("Skip invalid skill file: {}", skillFile, ex);
            return Optional.empty();
        }
    }

    private PromptSlice slicePrompt(String rawPrompt) {
        String prompt = rawPrompt == null ? "" : rawPrompt.trim();
        int maxPromptChars = Math.max(0, properties.getMaxPromptChars());
        if (maxPromptChars == 0 || prompt.length() <= maxPromptChars) {
            return new PromptSlice(prompt, false);
        }
        return new PromptSlice(
                prompt.substring(0, maxPromptChars)
                        + "\n\n[TRUNCATED: skill prompt exceeds maxPromptChars=" + maxPromptChars + "]",
                true
        );
    }

    private ParsedSkill parseSkillMarkdown(String raw) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        List<String> lines = normalized.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
            return new ParsedSkill(null, null, normalized.trim(), false);
        }

        int endIdx = -1;
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).trim())) {
                endIdx = i;
                break;
            }
        }
        if (endIdx < 0) {
            return new ParsedSkill(null, null, normalized.trim(), false);
        }

        String name = null;
        String description = null;
        boolean scaffold = false;
        for (int i = 1; i < endIdx; i++) {
            String line = lines.get(i).trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String key = line.substring(0, sep).trim().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(sep + 1).trim());
            if ("name".equals(key)) {
                name = value;
            } else if ("description".equals(key)) {
                description = value;
            } else if ("scaffold".equals(key)) {
                scaffold = "true".equalsIgnoreCase(value);
            }
        }

        List<String> bodyLines = new ArrayList<>();
        for (int i = endIdx + 1; i < lines.size(); i++) {
            bodyLines.add(lines.get(i));
        }
        String body = String.join("\n", bodyLines).trim();
        return new ParsedSkill(name, description, body, scaffold);
    }

    private String normalizeSkillId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isHiddenEntry(Path path) {
        if (path == null) {
            return false;
        }
        Path fileName = path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException ex) {
            return false;
        }
    }

    private String unquote(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2) {
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                    || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private record ParsedSkill(String name, String description, String body, boolean scaffold) {
    }

    private record PromptSlice(String prompt, boolean truncated) {
    }
}
