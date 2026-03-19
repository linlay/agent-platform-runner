package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ContainerHubMountResolver {

    private static final Logger log = LoggerFactory.getLogger(ContainerHubMountResolver.class);

    private final ContainerHubToolProperties containerHubProperties;
    private final ChatWindowMemoryProperties chatWindowMemoryProperties;
    private final SkillProperties skillProperties;
    private final AgentProperties agentProperties;
    private final ToolProperties toolProperties;
    private final ModelProperties modelProperties;
    private final ViewportProperties viewportProperties;
    private final TeamProperties teamProperties;
    private final ScheduleProperties scheduleProperties;
    private final McpProperties mcpProperties;
    private final ProviderProperties providerProperties;

    public ContainerHubMountResolver(
            ContainerHubToolProperties containerHubProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            SkillProperties skillProperties,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        this.containerHubProperties = containerHubProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.skillProperties = skillProperties;
        this.agentProperties = agentProperties;
        this.toolProperties = toolProperties;
        this.modelProperties = modelProperties;
        this.viewportProperties = viewportProperties;
        this.teamProperties = teamProperties;
        this.scheduleProperties = scheduleProperties;
        this.mcpProperties = mcpProperties;
        this.providerProperties = providerProperties;
    }

    public ContainerHubMountResolver(
            ContainerHubToolProperties containerHubProperties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            ToolProperties toolProperties,
            AgentProperties agentProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
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
                toolProperties,
                modelProperties,
                viewportProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties
        );
    }

    public List<MountSpec> resolve(
            SandboxLevel level,
            String chatId,
            String agentKey,
            List<AgentDefinition.ExtraMount> extraMounts
    ) {
        List<MountSpec> mounts = new ArrayList<>();
        Set<String> usedContainerPaths = new LinkedHashSet<>();
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
            usedContainerPaths.add("/tmp");
        }

        String userDir = mountConfig == null ? "./user" : mountConfig.getUserDir();
        if (StringUtils.hasText(userDir)) {
            addResolvedMount(mounts, usedContainerPaths, "user-dir", userDir, "/home");
        }

        String skillsDir = resolveSkillsDir(mountConfig);
        if (StringUtils.hasText(skillsDir)) {
            addResolvedMount(mounts, usedContainerPaths, "skills-dir", skillsDir, "/skills");
        }

        String panDir = mountConfig == null ? "./pan" : mountConfig.getPanDir();
        if (StringUtils.hasText(panDir)) {
            addResolvedMount(mounts, usedContainerPaths, "pan-dir", panDir, "/pan");
        }

        String agentSelfDir = resolveAgentSelfDir(mountConfig, agentKey);
        if (StringUtils.hasText(agentSelfDir)) {
            addResolvedMount(mounts, usedContainerPaths, "agent-self", agentSelfDir, "/agent");
        }

        if (extraMounts != null) {
            for (AgentDefinition.ExtraMount extraMount : extraMounts) {
                if (extraMount == null) {
                    continue;
                }
                if (extraMount.isPlatform()) {
                    resolvePlatformMount(mounts, usedContainerPaths, extraMount.platform(), mountConfig);
                } else {
                    resolveCustomMount(mounts, usedContainerPaths, extraMount);
                }
            }
        }

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

    private String resolveToolsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getToolsDir(), toolProperties == null ? null : toolProperties.getExternalDir());
    }

    private String resolveAgentsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getAgentsDir(), agentProperties == null ? null : agentProperties.getExternalDir());
    }

    private String resolveModelsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getModelsDir(), modelProperties == null ? null : modelProperties.getExternalDir());
    }

    private String resolveViewportsDir(ContainerHubToolProperties.MountConfig mountConfig) {
        return resolveDirectory(mountConfig == null ? null : mountConfig.getViewportsDir(), viewportProperties == null ? null : viewportProperties.getExternalDir());
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

    private String resolveAgentSelfDir(ContainerHubToolProperties.MountConfig mountConfig, String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return null;
        }
        String agentsDir = resolveAgentsDir(mountConfig);
        if (!StringUtils.hasText(agentsDir)) {
            return null;
        }
        Path agentDir = Path.of(agentsDir, agentKey).toAbsolutePath().normalize();
        return Files.isDirectory(agentDir) ? agentDir.toString() : null;
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

    private void resolvePlatformMount(
            List<MountSpec> mounts,
            Set<String> usedContainerPaths,
            String rawPlatform,
            ContainerHubToolProperties.MountConfig mountConfig
    ) {
        String platform = normalizePlatform(rawPlatform);
        PlatformMountDef platformMountDef = platformMountDefs(mountConfig).get(platform);
        if (platformMountDef == null) {
            log.warn("Skip unknown sandboxConfig.extraMounts platform '{}'", rawPlatform);
            return;
        }
        String source = platformMountDef.sourceSupplier().get();
        if (!StringUtils.hasText(source)) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount:%s: source is not configured (containerPath=%s)"
                            .formatted(platform, platformMountDef.containerPath())
            );
        }
        addResolvedMount(mounts, usedContainerPaths, "extra-mount:" + platform, source, platformMountDef.containerPath());
    }

    private void resolveCustomMount(
            List<MountSpec> mounts,
            Set<String> usedContainerPaths,
            AgentDefinition.ExtraMount extraMount
    ) {
        if (!StringUtils.hasText(extraMount.destination()) || !extraMount.destination().startsWith("/")) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount: destination must be an absolute path"
                            + " (destination=" + extraMount.destination() + ")"
            );
        }
        addResolvedMount(mounts, usedContainerPaths, "extra-mount", extraMount.source(), extraMount.destination());
    }

    private Map<String, PlatformMountDef> platformMountDefs(ContainerHubToolProperties.MountConfig mountConfig) {
        return Map.of(
                "models", new PlatformMountDef(() -> resolveModelsDir(mountConfig), "/models"),
                "tools", new PlatformMountDef(() -> resolveToolsDir(mountConfig), "/tools"),
                "agents", new PlatformMountDef(() -> resolveAgentsDir(mountConfig), "/agents"),
                "viewports", new PlatformMountDef(() -> resolveViewportsDir(mountConfig), "/viewports"),
                "teams", new PlatformMountDef(() -> resolveTeamsDir(mountConfig), "/teams"),
                "schedules", new PlatformMountDef(() -> resolveSchedulesDir(mountConfig), "/schedules"),
                "mcp-servers", new PlatformMountDef(() -> resolveMcpServersDir(mountConfig), "/mcp-servers"),
                "providers", new PlatformMountDef(() -> resolveProvidersDir(mountConfig), "/providers")
        );
    }

    private String normalizePlatform(String rawPlatform) {
        return rawPlatform == null ? "" : rawPlatform.trim().toLowerCase(Locale.ROOT);
    }

    private void addResolvedMount(
            List<MountSpec> mounts,
            Set<String> usedContainerPaths,
            String mountName,
            String rawPath,
            String containerPath
    ) {
        validateContainerPathConflict(usedContainerPaths, mountName, containerPath);
        String hostPath = requireExistingDirectory(mountName, rawPath, toAbsolute(rawPath), containerPath);
        mounts.add(new MountSpec(mountName, normalizeRawPath(rawPath), hostPath, containerPath));
        usedContainerPaths.add(containerPath);
    }

    private void validateContainerPathConflict(Set<String> usedContainerPaths, String mountName, String containerPath) {
        if (usedContainerPaths.contains(containerPath)) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for %s: containerPath conflicts with existing mount (containerPath=%s)"
                            .formatted(mountName, containerPath)
            );
        }
    }

    private String toAbsolute(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
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

    private record PlatformMountDef(Supplier<String> sourceSupplier, String containerPath) {
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath) {
    }
}
