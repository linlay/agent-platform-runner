package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ContainerHubMountResolver {

    private final ContainerHubToolProperties containerHubProperties;
    private final DataProperties dataProperties;
    private final SkillProperties skillProperties;

    public ContainerHubMountResolver(
            ContainerHubToolProperties containerHubProperties,
            DataProperties dataProperties,
            SkillProperties skillProperties
    ) {
        this.containerHubProperties = containerHubProperties;
        this.dataProperties = dataProperties;
        this.skillProperties = skillProperties;
    }

    public List<MountSpec> resolve(SandboxLevel level, String chatId) {
        List<MountSpec> mounts = new ArrayList<>();
        ContainerHubToolProperties.MountConfig mountConfig = containerHubProperties.getMounts();

        String dataDir = resolveDataDir(mountConfig);
        if (StringUtils.hasText(dataDir)) {
            String hostPath;
            if (level == SandboxLevel.RUN && StringUtils.hasText(chatId)) {
                hostPath = prepareRunDataMountDirectory(dataDir, chatId);
            } else {
                hostPath = requireExistingDirectory("data-dir", dataDir, toAbsolute(dataDir), "/tmp");
            }
            mounts.add(new MountSpec("data-dir", normalizeRawPath(dataDir), hostPath, "/tmp"));
        }

        String userDir = mountConfig == null ? "./user" : mountConfig.getUserDir();
        if (StringUtils.hasText(userDir)) {
            mounts.add(existingDirectoryMount("user-dir", userDir, "/home"));
        }

        String skillsDir = resolveSkillsDir(mountConfig);
        if (StringUtils.hasText(skillsDir)) {
            mounts.add(existingDirectoryMount("skills-dir", skillsDir, "/skills"));
        }

        String panDir = mountConfig == null ? "./pan" : mountConfig.getPanDir();
        if (StringUtils.hasText(panDir)) {
            mounts.add(existingDirectoryMount("pan-dir", panDir, "/pan"));
        }

        return List.copyOf(mounts);
    }

    private String resolveDataDir(ContainerHubToolProperties.MountConfig mountConfig) {
        if (mountConfig != null && StringUtils.hasText(mountConfig.getDataDir())) {
            return mountConfig.getDataDir().trim();
        }
        if (dataProperties != null && StringUtils.hasText(dataProperties.getExternalDir())) {
            return dataProperties.getExternalDir().trim();
        }
        return null;
    }

    private String resolveSkillsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        if (mountConfig != null && StringUtils.hasText(mountConfig.getSkillsDir())) {
            return mountConfig.getSkillsDir().trim();
        }
        if (skillProperties != null && StringUtils.hasText(skillProperties.getExternalDir())) {
            return skillProperties.getExternalDir().trim();
        }
        return null;
    }

    private String toAbsolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private MountSpec existingDirectoryMount(String mountName, String rawPath, String containerPath) {
        String hostPath = requireExistingDirectory(mountName, rawPath, toAbsolute(rawPath), containerPath);
        return new MountSpec(mountName, normalizeRawPath(rawPath), hostPath, containerPath);
    }

    private String requireExistingDirectory(String mountName, String rawPath, String resolvedPath, String containerPath) {
        Path path = Path.of(resolvedPath);
        if (Files.isDirectory(path)) {
            return path.toAbsolutePath().normalize().toString();
        }
        String reason = Files.exists(path) ? "source is not a directory" : "source does not exist";
        throw new IllegalStateException("container-hub mount validation failed for %s: %s (configured=%s, resolved=%s, containerPath=%s)"
                .formatted(mountName, reason, normalizeRawPath(rawPath), path.toAbsolutePath().normalize(), containerPath));
    }

    private String prepareRunDataMountDirectory(String dataDir, String chatId) {
        Path path = Path.of(dataDir, chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/tmp)".formatted(
                            normalizeRawPath(dataDir),
                            path
                    ),
                    ex
            );
        }
        return path.toString();
    }

    private String normalizeRawPath(String rawPath) {
        return rawPath == null ? "" : rawPath.trim();
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath) {
    }
}
