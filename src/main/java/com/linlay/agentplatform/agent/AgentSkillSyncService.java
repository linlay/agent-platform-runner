package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.skill.SkillProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class AgentSkillSyncService {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillSyncService.class);
    static final String LOCAL_SKILLS_DIR = "skills";
    static final String MANIFEST_FILE = ".market-synced-skills";

    private final Path skillsMarketDir;

    @Autowired
    public AgentSkillSyncService(SkillProperties skillProperties) {
        this(skillProperties == null ? null : skillProperties.getExternalDir());
    }

    static AgentSkillSyncService forTesting(String skillsMarketDir) {
        return new AgentSkillSyncService(skillsMarketDir);
    }

    private AgentSkillSyncService(String skillsMarketDir) {
        this.skillsMarketDir = normalizePath(skillsMarketDir);
    }

    public void reconcileDeclaredSkills(@Nullable Path agentDir, @Nullable List<String> declaredSkills) {
        Path normalizedAgentDir = normalizePath(agentDir);
        if (normalizedAgentDir == null || !Files.isDirectory(normalizedAgentDir)) {
            return;
        }

        Path localSkillsDir = normalizedAgentDir.resolve(LOCAL_SKILLS_DIR).toAbsolutePath().normalize();
        if (!localSkillsDir.startsWith(normalizedAgentDir)) {
            log.warn("Skip agent skill sync because local skills dir escapes agent directory: {}", localSkillsDir);
            return;
        }
        try {
            Files.createDirectories(localSkillsDir);
        } catch (IOException ex) {
            log.warn("Failed to prepare local skills directory for agent {}", normalizedAgentDir, ex);
            return;
        }

        Set<String> desiredSkillIds = normalizeSkillIds(declaredSkills);
        Set<String> previousManagedSkillIds = readManifest(localSkillsDir.resolve(MANIFEST_FILE));

        for (String skillId : desiredSkillIds) {
            Path sourceDir = resolveMarketSkillDir(skillId);
            if (sourceDir == null || !Files.isDirectory(sourceDir)) {
                log.warn("Skip syncing declared skill '{}' for agent {} because market source is missing", skillId, normalizedAgentDir);
                continue;
            }
            Path targetDir = localSkillsDir.resolve(skillId).toAbsolutePath().normalize();
            if (!targetDir.startsWith(localSkillsDir)) {
                log.warn("Skip syncing declared skill '{}' for agent {} because target escapes local skills dir", skillId, normalizedAgentDir);
                continue;
            }
            try {
                deleteRecursively(targetDir);
                copyRecursively(sourceDir, targetDir);
            } catch (IOException ex) {
                log.warn("Failed to sync declared skill '{}' for agent {}", skillId, normalizedAgentDir, ex);
            }
        }

        for (String staleSkillId : previousManagedSkillIds) {
            if (desiredSkillIds.contains(staleSkillId)) {
                continue;
            }
            Path staleDir = localSkillsDir.resolve(staleSkillId).toAbsolutePath().normalize();
            if (!staleDir.startsWith(localSkillsDir)) {
                continue;
            }
            try {
                deleteRecursively(staleDir);
            } catch (IOException ex) {
                log.warn("Failed to remove stale synced skill '{}' for agent {}", staleSkillId, normalizedAgentDir, ex);
            }
        }

        writeManifest(localSkillsDir.resolve(MANIFEST_FILE), desiredSkillIds);
    }

    private Path resolveMarketSkillDir(String skillId) {
        if (skillsMarketDir == null || !StringUtils.hasText(skillId)) {
            return null;
        }
        Path resolved = skillsMarketDir.resolve(skillId).toAbsolutePath().normalize();
        return resolved.startsWith(skillsMarketDir) ? resolved : null;
    }

    private Set<String> readManifest(Path manifestFile) {
        if (manifestFile == null || !Files.isRegularFile(manifestFile)) {
            return Set.of();
        }
        try {
            return normalizeSkillIds(Files.readAllLines(manifestFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn("Failed to read synced skill manifest {}", manifestFile, ex);
            return Set.of();
        }
    }

    private void writeManifest(Path manifestFile, Set<String> skillIds) {
        if (manifestFile == null) {
            return;
        }
        try {
            if (skillIds == null || skillIds.isEmpty()) {
                Files.deleteIfExists(manifestFile);
                return;
            }
            if (manifestFile.getParent() != null) {
                Files.createDirectories(manifestFile.getParent());
            }
            Files.write(manifestFile, skillIds, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Failed to write synced skill manifest {}", manifestFile, ex);
        }
    }

    private Set<String> normalizeSkillIds(@Nullable List<String> rawSkillIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (rawSkillIds == null || rawSkillIds.isEmpty()) {
            return Set.of();
        }
        for (String rawSkillId : rawSkillIds) {
            if (!StringUtils.hasText(rawSkillId)) {
                continue;
            }
            normalized.add(rawSkillId.trim().toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private void copyRecursively(Path sourceDir, Path targetDir) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            for (Path source : stream.sorted().toList()) {
                Path relative = sourceDir.relativize(source);
                Path target = relative.getNameCount() == 0
                        ? targetDir
                        : targetDir.resolve(relative).toAbsolutePath().normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("refusing to copy outside target dir: " + target);
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private Path normalizePath(@Nullable Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private Path normalizePath(@Nullable String path) {
        return StringUtils.hasText(path) ? Path.of(path).toAbsolutePath().normalize() : null;
    }
}
