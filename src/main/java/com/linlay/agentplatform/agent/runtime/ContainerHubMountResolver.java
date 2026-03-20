package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.PanProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.config.ViewportServerProperties;
import com.linlay.agentplatform.config.RootProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class ContainerHubMountResolver {

    private static final Logger log = LoggerFactory.getLogger(ContainerHubMountResolver.class);
    static final String WORKSPACE_PATH = "/workspace";
    static final String ROOT_PATH = "/root";
    static final String SKILLS_PATH = "/skills";
    static final String PAN_PATH = "/pan";
    static final String AGENT_PATH = "/agent";
    private static final Set<String> DEFAULT_OVERRIDEABLE_PATHS = Set.of(
            WORKSPACE_PATH,
            ROOT_PATH,
            SKILLS_PATH,
            PAN_PATH,
            AGENT_PATH
    );

    private final ChatWindowMemoryProperties chatWindowMemoryProperties;
    private final RootProperties rootProperties;
    private final PanProperties panProperties;
    private final SkillProperties skillProperties;
    private final AgentProperties agentProperties;
    private final ToolProperties toolProperties;
    private final ModelProperties modelProperties;
    private final ViewportProperties viewportProperties;
    private final ViewportServerProperties viewportServerProperties;
    private final TeamProperties teamProperties;
    private final ScheduleProperties scheduleProperties;
    private final McpProperties mcpProperties;
    private final ProviderProperties providerProperties;

    public ContainerHubMountResolver(
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            SkillProperties skillProperties,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
            ViewportServerProperties viewportServerProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.rootProperties = rootProperties;
        this.panProperties = panProperties;
        this.skillProperties = skillProperties;
        this.agentProperties = agentProperties;
        this.toolProperties = toolProperties;
        this.modelProperties = modelProperties;
        this.viewportProperties = viewportProperties;
        this.viewportServerProperties = viewportServerProperties;
        this.teamProperties = teamProperties;
        this.scheduleProperties = scheduleProperties;
        this.mcpProperties = mcpProperties;
        this.providerProperties = providerProperties;
    }

    public ContainerHubMountResolver(
            DataProperties dataProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            SkillProperties skillProperties,
            ToolProperties toolProperties,
            AgentProperties agentProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
            ViewportServerProperties viewportServerProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        this(
                toChatWindowMemoryProperties(dataProperties),
                rootProperties,
                panProperties,
                skillProperties,
                agentProperties,
                toolProperties,
                modelProperties,
                viewportProperties,
                viewportServerProperties,
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
        Map<String, MountSpec> mountsByContainerPath = new LinkedHashMap<>();

        String dataDir = resolveDataDir();
        if (StringUtils.hasText(dataDir)) {
            String hostPath;
            if (level == SandboxLevel.RUN && StringUtils.hasText(chatId)) {
                hostPath = prepareRunDataMountDirectory(dataDir, chatId);
            } else {
                hostPath = requireExistingPath(
                        "data-dir",
                        dataDir,
                        toAbsolute(dataDir),
                        WORKSPACE_PATH,
                        MountSourceType.DIRECTORY
                );
                if (StringUtils.hasText(chatId)) {
                    prepareSharedDataMountDirectory(hostPath, chatId);
                }
            }
            addMount(mountsByContainerPath, new MountSpec("data-dir", normalizeRawPath(dataDir), hostPath, WORKSPACE_PATH, false));
        }

        String rootDir = resolveRootDir();
        if (StringUtils.hasText(rootDir)) {
            addResolvedMount(mountsByContainerPath, "root-dir", rootDir, ROOT_PATH, false);
        }

        String skillsDir = resolveSkillsDir();
        if (StringUtils.hasText(skillsDir)) {
            addResolvedMount(mountsByContainerPath, "skills-dir", skillsDir, SKILLS_PATH, true);
        }

        String panDir = resolvePanDir();
        if (StringUtils.hasText(panDir)) {
            addResolvedMount(mountsByContainerPath, "pan-dir", panDir, PAN_PATH, false);
        }

        String agentSelfDir = resolveAgentSelfDir(agentKey);
        if (StringUtils.hasText(agentSelfDir)) {
            addResolvedMount(mountsByContainerPath, "agent-self", agentSelfDir, AGENT_PATH, true);
        }

        if (extraMounts != null) {
            for (AgentDefinition.ExtraMount extraMount : extraMounts) {
                if (extraMount == null) {
                    continue;
                }
                String destination = normalizeContainerPath(extraMount.destination());
                if (isDefaultMountOverride(extraMount, destination)) {
                    applyDefaultMountOverride(mountsByContainerPath, destination, extraMount.mode());
                    continue;
                }
                if (extraMount.isPlatform()) {
                    resolvePlatformMount(mountsByContainerPath, extraMount.platform(), extraMount.mode());
                } else {
                    resolveCustomMount(mountsByContainerPath, extraMount, destination);
                }
            }
        }

        return List.copyOf(mountsByContainerPath.values());
    }

    private String resolveDataDir() {
        if (chatWindowMemoryProperties != null && StringUtils.hasText(chatWindowMemoryProperties.getDir())) {
            return chatWindowMemoryProperties.getDir().trim();
        }
        return null;
    }

    private String resolveRootDir() {
        if (rootProperties != null && StringUtils.hasText(rootProperties.getExternalDir())) {
            return rootProperties.getExternalDir().trim();
        }
        return null;
    }

    private String resolveSkillsDir() {
        if (skillProperties != null && StringUtils.hasText(skillProperties.getExternalDir())) {
            return skillProperties.getExternalDir().trim();
        }
        return null;
    }

    private String resolvePanDir() {
        if (panProperties != null && StringUtils.hasText(panProperties.getExternalDir())) {
            return panProperties.getExternalDir().trim();
        }
        return null;
    }

    private String resolveToolsDir() {
        return resolveDirectory(toolProperties == null ? null : toolProperties.getExternalDir());
    }

    private String resolveChatsDir() {
        return resolveDataDir();
    }

    private String resolveAgentsDir() {
        return resolveDirectory(agentProperties == null ? null : agentProperties.getExternalDir());
    }

    private String resolveModelsDir() {
        return resolveDirectory(modelProperties == null ? null : modelProperties.getExternalDir());
    }

    private String resolveViewportsDir() {
        return resolveDirectory(viewportProperties == null ? null : viewportProperties.getExternalDir());
    }

    private String resolveViewportServersDir() {
        return resolveDirectory(viewportServerProperties == null || viewportServerProperties.getRegistry() == null
                ? null
                : viewportServerProperties.getRegistry().getExternalDir());
    }

    private String resolveTeamsDir() {
        return resolveDirectory(teamProperties == null ? null : teamProperties.getExternalDir());
    }

    private String resolveSchedulesDir() {
        return resolveDirectory(scheduleProperties == null ? null : scheduleProperties.getExternalDir());
    }

    private String resolveMcpServersDir() {
        return resolveDirectory(mcpProperties == null || mcpProperties.getRegistry() == null
                ? null
                : mcpProperties.getRegistry().getExternalDir());
    }

    private String resolveProvidersDir() {
        return resolveDirectory(providerProperties == null ? null : providerProperties.getExternalDir());
    }

    private String resolveAgentSelfDir(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return null;
        }
        String agentsDir = resolveAgentsDir();
        if (!StringUtils.hasText(agentsDir)) {
            return null;
        }
        Path agentDir = Path.of(agentsDir, agentKey).toAbsolutePath().normalize();
        return Files.isDirectory(agentDir) ? agentDir.toString() : null;
    }

    private String resolveOwnerFilePath() {
        String agentsDir = resolveAgentsDir();
        if (!StringUtils.hasText(agentsDir)) {
            return null;
        }
        Path agentsPath = Path.of(agentsDir).toAbsolutePath().normalize();
        Path parent = agentsPath.getParent();
        if (parent == null) {
            return null;
        }
        return parent.resolve("OWNER.md").toString();
    }

    private String resolveDirectory(String path) {
        if (StringUtils.hasText(path)) {
            return path.trim();
        }
        return null;
    }

    private void resolvePlatformMount(
            Map<String, MountSpec> mountsByContainerPath,
            String rawPlatform,
            MountAccessMode mode
    ) {
        String platform = normalizePlatform(rawPlatform);
        PlatformMountDef platformMountDef = platformMountDefs().get(platform);
        if (platformMountDef == null) {
            log.warn("Skip unknown sandboxConfig.extraMounts platform '{}'", rawPlatform);
            return;
        }
        if (mode == null) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount:%s: mode is required"
                            .formatted(platform)
            );
        }
        String source = platformMountDef.sourceSupplier().get();
        if (!StringUtils.hasText(source)) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount:%s: source is not configured (containerPath=%s)"
                            .formatted(platform, platformMountDef.containerPath())
            );
        }
        addResolvedMount(
                mountsByContainerPath,
                "extra-mount:" + platform,
                source,
                platformMountDef.containerPath(),
                mode.readOnly(),
                platformMountDef.sourceType()
        );
    }

    private void resolveCustomMount(
            Map<String, MountSpec> mountsByContainerPath,
            AgentDefinition.ExtraMount extraMount,
            String normalizedDestination
    ) {
        if (extraMount.mode() == null) {
            throw new IllegalStateException("container-hub mount validation failed for extra-mount: mode is required");
        }
        if (StringUtils.hasText(normalizedDestination) && DEFAULT_OVERRIDEABLE_PATHS.contains(normalizedDestination)) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount: overriding a default mount "
                            + "must omit source/platform and only declare destination + mode "
                            + "(destination=" + normalizedDestination + ")"
            );
        }
        if (!StringUtils.hasText(extraMount.source()) || !StringUtils.hasText(normalizedDestination)) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount: custom mount requires source + destination + mode"
            );
        }
        if (!normalizedDestination.startsWith("/")) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for extra-mount: destination must be an absolute path"
                            + " (destination=" + extraMount.destination() + ")"
            );
        }
        addResolvedMount(mountsByContainerPath, "extra-mount", extraMount.source(), normalizedDestination, extraMount.mode().readOnly());
    }

    private Map<String, PlatformMountDef> platformMountDefs() {
        return Map.ofEntries(
                Map.entry("models", new PlatformMountDef(this::resolveModelsDir, "/models", MountSourceType.DIRECTORY)),
                Map.entry("tools", new PlatformMountDef(this::resolveToolsDir, "/tools", MountSourceType.DIRECTORY)),
                Map.entry("agents", new PlatformMountDef(this::resolveAgentsDir, "/agents", MountSourceType.DIRECTORY)),
                Map.entry("viewports", new PlatformMountDef(this::resolveViewportsDir, "/viewports", MountSourceType.DIRECTORY)),
                Map.entry("viewport-servers", new PlatformMountDef(this::resolveViewportServersDir, "/viewport-servers", MountSourceType.DIRECTORY)),
                Map.entry("teams", new PlatformMountDef(this::resolveTeamsDir, "/teams", MountSourceType.DIRECTORY)),
                Map.entry("schedules", new PlatformMountDef(this::resolveSchedulesDir, "/schedules", MountSourceType.DIRECTORY)),
                Map.entry("mcp-servers", new PlatformMountDef(this::resolveMcpServersDir, "/mcp-servers", MountSourceType.DIRECTORY)),
                Map.entry("providers", new PlatformMountDef(this::resolveProvidersDir, "/providers", MountSourceType.DIRECTORY)),
                Map.entry("chats", new PlatformMountDef(this::resolveChatsDir, "/chats", MountSourceType.DIRECTORY)),
                Map.entry("owner.md", new PlatformMountDef(this::resolveOwnerFilePath, "/OWNER.md", MountSourceType.FILE))
        );
    }

    private String normalizePlatform(String rawPlatform) {
        return rawPlatform == null ? "" : rawPlatform.trim().toLowerCase(Locale.ROOT);
    }

    private void addResolvedMount(
            Map<String, MountSpec> mountsByContainerPath,
            String mountName,
            String rawPath,
            String containerPath,
            boolean readOnly
    ) {
        addResolvedMount(mountsByContainerPath, mountName, rawPath, containerPath, readOnly, MountSourceType.DIRECTORY);
    }

    private void addResolvedMount(
            Map<String, MountSpec> mountsByContainerPath,
            String mountName,
            String rawPath,
            String containerPath,
            boolean readOnly,
            MountSourceType sourceType
    ) {
        validateContainerPathConflict(mountsByContainerPath.keySet(), mountName, containerPath);
        String hostPath = requireExistingPath(mountName, rawPath, toAbsolute(rawPath), containerPath, sourceType);
        addMount(mountsByContainerPath, new MountSpec(mountName, normalizeRawPath(rawPath), hostPath, containerPath, readOnly));
    }

    private void addMount(Map<String, MountSpec> mountsByContainerPath, MountSpec mount) {
        mountsByContainerPath.put(mount.containerPath(), mount);
    }

    private void applyDefaultMountOverride(
            Map<String, MountSpec> mountsByContainerPath,
            String destination,
            MountAccessMode mode
    ) {
        if (mode == null) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for default-mount-override: mode is required "
                            + "(destination=" + destination + ")"
            );
        }
        MountSpec existing = mountsByContainerPath.get(destination);
        if (existing == null) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for default-mount-override: default mount is not available "
                            + "(destination=" + destination + ")"
            );
        }
        addMount(mountsByContainerPath, new MountSpec(
                existing.mountName(),
                existing.rawPath(),
                existing.hostPath(),
                existing.containerPath(),
                mode.readOnly()
        ));
    }

    private boolean isDefaultMountOverride(AgentDefinition.ExtraMount extraMount, String destination) {
        return extraMount != null
                && !extraMount.isPlatform()
                && !StringUtils.hasText(extraMount.source())
                && StringUtils.hasText(destination)
                && DEFAULT_OVERRIDEABLE_PATHS.contains(destination);
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

    private String requireExistingPath(
            String mountName,
            String rawPath,
            String resolvedPath,
            String containerPath,
            MountSourceType sourceType
    ) {
        Path path = Path.of(resolvedPath);
        if (sourceType == MountSourceType.DIRECTORY && Files.isDirectory(path)) {
            return path.toAbsolutePath().normalize().toString();
        }
        if (sourceType == MountSourceType.FILE && Files.isRegularFile(path)) {
            return path.toAbsolutePath().normalize().toString();
        }
        String reason;
        if (!Files.exists(path)) {
            reason = "source does not exist";
        } else if (sourceType == MountSourceType.FILE) {
            reason = "source is not a file";
        } else {
            reason = "source is not a directory";
        }
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
                            + "(configured=%s, resolved=%s, containerPath=/workspace)".formatted(
                            normalizeRawPath(dataDir),
                            path
                    ),
                    ex
            );
        }
        return path.toString();
    }

    private void prepareSharedDataMountDirectory(String dataDir, String chatId) {
        Path path = Path.of(dataDir, chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/workspace/%s)".formatted(
                            normalizeRawPath(dataDir),
                            path,
                            chatId.trim()
                    ),
                    ex
            );
        }
    }

    private String normalizeRawPath(String rawPath) {
        return rawPath == null ? "" : rawPath.trim();
    }

    private String normalizeContainerPath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        return Path.of(path.trim()).normalize().toString();
    }

    private static ChatWindowMemoryProperties toChatWindowMemoryProperties(DataProperties dataProperties) {
        ChatWindowMemoryProperties properties = new ChatWindowMemoryProperties();
        if (dataProperties != null && StringUtils.hasText(dataProperties.getExternalDir())) {
            properties.setDir(dataProperties.getExternalDir());
        }
        return properties;
    }

    private record PlatformMountDef(Supplier<String> sourceSupplier, String containerPath, MountSourceType sourceType) {
    }

    private enum MountSourceType {
        DIRECTORY,
        FILE
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath, boolean readOnly) {
    }
}
