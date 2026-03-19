package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ContainerHubMountResolver {

    private final ContainerHubToolProperties containerHubProperties;
    private final ChatWindowMemoryProperties chatWindowMemoryProperties;
    private final SkillProperties skillProperties;
    private final AgentProperties agentProperties;
    private final ModelProperties modelProperties;
    private final TeamProperties teamProperties;
    private final ScheduleProperties scheduleProperties;
    private final McpProperties mcpProperties;
    private final ProviderProperties providerProperties;

    public ContainerHubMountResolver(
            ContainerHubToolProperties containerHubProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            SkillProperties skillProperties,
            AgentProperties agentProperties,
            ModelProperties modelProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        this.containerHubProperties = containerHubProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.skillProperties = skillProperties;
        this.agentProperties = agentProperties;
        this.modelProperties = modelProperties;
        this.teamProperties = teamProperties;
        this.scheduleProperties = scheduleProperties;
        this.mcpProperties = mcpProperties;
        this.providerProperties = providerProperties;
    }

    public ContainerHubMountResolver(
            ContainerHubToolProperties containerHubProperties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            ToolProperties ignoredToolProperties,
            AgentProperties agentProperties,
            ModelProperties modelProperties,
            ViewportProperties ignoredViewportProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        this(
                containerHubProperties,
                toChatWindowMemoryProperties(dataProperties),
                skillProperties,
                agentProperties,
                modelProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties
        );
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

        addOptionalDirectoryMount(mounts, "agents-dir", resolveAgentsDir(mountConfig), "/agents");
        addOptionalDirectoryMount(mounts, "models-dir", resolveModelsDir(mountConfig), "/models");
        addOptionalDirectoryMount(mounts, "teams-dir", resolveTeamsDir(mountConfig), "/teams");
        addOptionalDirectoryMount(mounts, "schedules-dir", resolveSchedulesDir(mountConfig), "/schedules");
        addOptionalDirectoryMount(mounts, "mcp-servers-dir", resolveMcpServersDir(mountConfig), "/mcp-servers");
        addOptionalDirectoryMount(mounts, "providers-dir", resolveProvidersDir(mountConfig), "/providers");

        return List.copyOf(mounts);
    }

    private String resolveDataDir(ContainerHubToolProperties.MountConfig mountConfig) {
        if (mountConfig != null && StringUtils.hasText(mountConfig.getDataDir())) {
            return mountConfig.getDataDir().trim();
        }
        if (chatWindowMemoryProperties != null && StringUtils.hasText(chatWindowMemoryProperties.getDir())) {
            return chatWindowMemoryProperties.getDir().trim();
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

    private String resolveAgentsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getAgentsDir(), agentProperties == null ? null : agentProperties.getExternalDir());
    }

    private String resolveModelsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getModelsDir(), modelProperties == null ? null : modelProperties.getExternalDir());
    }

    private String resolveTeamsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getTeamsDir(), teamProperties == null ? null : teamProperties.getExternalDir());
    }

    private String resolveSchedulesDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getSchedulesDir(), scheduleProperties == null ? null : scheduleProperties.getExternalDir());
    }

    private String resolveMcpServersDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(
                mountConfig == null ? null : mountConfig.getMcpServersDir(),
                mcpProperties == null || mcpProperties.getRegistry() == null ? null : mcpProperties.getRegistry().getExternalDir()
        );
    }

    private String resolveProvidersDir(ContainerHubToolProperties.MountConfig mountConfig) {
        if (mountConfig == null || !StringUtils.hasText(mountConfig.getProvidersDir())) {
            return null;
        }
        return mountConfig.getProvidersDir().trim();
    }

    private String resolveDirectory(String configuredPath, String fallbackPath) {
        if (StringUtils.hasText(configuredPath)) {
            return configuredPath.trim();
        }
        if (StringUtils.hasText(fallbackPath)) {
            return fallbackPath.trim();
        }
        return null;
    }

    private String toAbsolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private void addOptionalDirectoryMount(List<MountSpec> mounts, String mountName, String rawPath, String containerPath) {
        if (!StringUtils.hasText(rawPath)) {
            return;
        }
        mounts.add(existingDirectoryMount(mountName, rawPath, containerPath));
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

    private static ChatWindowMemoryProperties toChatWindowMemoryProperties(DataProperties dataProperties) {
        ChatWindowMemoryProperties properties = new ChatWindowMemoryProperties();
        if (dataProperties != null && StringUtils.hasText(dataProperties.getExternalDir())) {
            properties.setDir(dataProperties.getExternalDir());
        }
        return properties;
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath) {
    }
}
