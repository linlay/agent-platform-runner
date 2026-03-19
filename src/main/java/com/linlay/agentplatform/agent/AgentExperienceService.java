package com.linlay.agentplatform.agent;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AgentExperienceService {

    public List<ExperienceEntry> loadExperiences(Path agentDir) {
        Path root = experiencesRoot(agentDir);
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<ExperienceEntry> entries = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> toEntry(root, path).ifPresent(entries::add));
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(entries);
    }

    public List<ExperienceEntry> loadExperiencesForSkill(Path agentDir, String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return List.of();
        }
        String normalizedSkillId = skillId.trim().toLowerCase(Locale.ROOT);
        return loadExperiences(agentDir).stream()
                .filter(entry -> normalizedSkillId.equals(entry.category()))
                .toList();
    }

    public String formatForPrompt(List<ExperienceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (ExperienceEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.content())) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            block.append("experience: ").append(entry.relativePath());
            if (StringUtils.hasText(entry.category())) {
                block.append("\ncategory: ").append(entry.category());
            }
            block.append("\ncontent:\n").append(entry.content());
            blocks.add(block.toString());
        }
        if (blocks.isEmpty()) {
            return "";
        }
        return "相关经验（按需参考，不要机械照搬）:\n\n" + String.join("\n\n---\n\n", blocks);
    }

    private java.util.Optional<ExperienceEntry> toEntry(Path root, Path file) {
        try {
            String content = Files.readString(file);
            if (!StringUtils.hasText(content)) {
                return java.util.Optional.empty();
            }
            Path relative = root.relativize(file);
            String relativePath = relative.toString().replace('\\', '/');
            String category = relative.getNameCount() > 1
                    ? relative.getName(0).toString().trim().toLowerCase(Locale.ROOT)
                    : "";
            return java.util.Optional.of(new ExperienceEntry(relativePath, category, content.trim()));
        } catch (IOException ignored) {
            return java.util.Optional.empty();
        }
    }

    private Path experiencesRoot(Path agentDir) {
        if (agentDir == null) {
            return null;
        }
        return agentDir.toAbsolutePath().normalize().resolve("experiences");
    }

    public record ExperienceEntry(
            String relativePath,
            String category,
            String content
    ) {
    }
}
