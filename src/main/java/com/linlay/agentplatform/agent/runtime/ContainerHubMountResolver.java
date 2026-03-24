package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.config.properties.PanProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.properties.ToolProperties;
import com.linlay.agentplatform.config.properties.ViewportProperties;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
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
    private final RuntimeDirectoryHostPaths hostPaths;

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
        this(
                chatWindowMemoryProperties,
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
                providerProperties,
                new RuntimeDirectoryHostPaths(Map.of())
        );
    }

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
            ProviderProperties providerProperties,
            RuntimeDirectoryHostPaths hostPaths
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
        this.hostPaths = hostPaths == null ? new RuntimeDirectoryHostPaths(Map.of()) : hostPaths;
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
                dataProperties,
                rootProperties,
                panProperties,
                skillProperties,
                toolProperties,
                agentProperties,
                modelProperties,
                viewportProperties,
                viewportServerProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties,
                new RuntimeDirectoryHostPaths(Map.of())
        );
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
            ProviderProperties providerProperties,
            RuntimeDirectoryHostPaths hostPaths
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
                providerProperties,
                hostPaths
        );
    }

    public List<MountSpec> resolve(
            SandboxLevel level,
            String chatId,
            String agentKey,
            List<AgentDefinition.ExtraMount> extraMounts
    ) {
        Map<String, MountSpec> mountsByContainerPath = new LinkedHashMap<>();

        ResolvedPath dataDir = resolveDataDir();
        if (dataDir != null) {
            String mountSourcePath;
            if (level == SandboxLevel.RUN && StringUtils.hasText(chatId)) {
                mountSourcePath = prepareRunDataMountDirectory(dataDir, chatId);
            } else {
                validateExistingPath(
                        "data-dir",
                        dataDir.rawPath(),
                        dataDir.accessPath(),
                        WORKSPACE_PATH,
                        MountSourceType.DIRECTORY
                );
                if (StringUtils.hasText(chatId)) {
                    prepareSharedDataMountDirectory(dataDir, chatId);
                }
                mountSourcePath = normalizeMountSourcePath(dataDir.mountSourcePath());
            }
            addMount(mountsByContainerPath, new MountSpec("data-dir", dataDir.rawPath(), mountSourcePath, WORKSPACE_PATH, false));
        }

        ResolvedPath rootDir = resolveRootDir();
        if (rootDir != null) {
            addResolvedMount(mountsByContainerPath, "root-dir", rootDir, ROOT_PATH, false);
        }

        ResolvedPath skillsDir = resolveSkillsDir(level, agentKey);
        if (skillsDir != null) {
            addResolvedMount(mountsByContainerPath, "skills-dir", skillsDir, SKILLS_PATH, true);
        }

        ResolvedPath panDir = resolvePanDir();
        if (panDir != null) {
            addResolvedMount(mountsByContainerPath, "pan-dir", panDir, PAN_PATH, false);
        }

        ResolvedPath agentSelfDir = resolveAgentSelfDir(agentKey);
        if (agentSelfDir != null) {
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

    private ResolvedPath resolveDataDir() {
        return resolveHostBackedDirectory("CHATS_DIR", directoryValue(chatWindowMemoryProperties == null ? null : chatWindowMemoryProperties.getDir()), "data-dir");
    }

    private ResolvedPath resolveRootDir() {
        return resolveHostBackedDirectory("ROOT_DIR", directoryValue(rootProperties == null ? null : rootProperties.getExternalDir()), "root-dir");
    }

    private ResolvedPath resolveGlobalSkillsDir() {
        return resolveHostBackedDirectory("SKILLS_MARKET_DIR", directoryValue(skillProperties == null ? null : skillProperties.getExternalDir()), "skills-dir");
    }

    private ResolvedPath resolveSkillsDir(SandboxLevel level, String agentKey) {
        if (level == SandboxLevel.GLOBAL) {
            return resolveGlobalSkillsDir();
        }
        ResolvedPath localSkillsDir = resolveAgentSkillsDir(agentKey);
        if (localSkillsDir != null) {
            return localSkillsDir;
        }
        return resolveGlobalSkillsDir();
    }

    private ResolvedPath resolvePanDir() {
        return resolveHostBackedDirectory("PAN_DIR", directoryValue(panProperties == null ? null : panProperties.getExternalDir()), "pan-dir");
    }

    private ResolvedPath resolveToolsDir() {
        return resolveDirectPath(directoryValue(toolProperties == null ? null : toolProperties.getExternalDir()));
    }

    private ResolvedPath resolveChatsDir() {
        return resolveDataDir();
    }

    private ResolvedPath resolveAgentsDir() {
        return resolveHostBackedDirectory("AGENTS_DIR", directoryValue(agentProperties == null ? null : agentProperties.getExternalDir()), "agents-dir");
    }

    private ResolvedPath resolveModelsDir() {
        return resolveHostBackedDirectory("MODELS_DIR", directoryValue(modelProperties == null ? null : modelProperties.getExternalDir()), "models-dir");
    }

    private ResolvedPath resolveViewportsDir() {
        return resolveDirectPath(directoryValue(viewportProperties == null ? null : viewportProperties.getExternalDir()));
    }

    private ResolvedPath resolveViewportServersDir() {
        return resolveHostBackedDirectory(
                "VIEWPORT_SERVERS_DIR",
                directoryValue(viewportServerProperties == null || viewportServerProperties.getRegistry() == null
                        ? null
                        : viewportServerProperties.getRegistry().getExternalDir()),
                "viewport-servers-dir"
        );
    }

    private ResolvedPath resolveTeamsDir() {
        return resolveHostBackedDirectory("TEAMS_DIR", directoryValue(teamProperties == null ? null : teamProperties.getExternalDir()), "teams-dir");
    }

    private ResolvedPath resolveSchedulesDir() {
        return resolveHostBackedDirectory("SCHEDULES_DIR", directoryValue(scheduleProperties == null ? null : scheduleProperties.getExternalDir()), "schedules-dir");
    }

    private ResolvedPath resolveMcpServersDir() {
        return resolveHostBackedDirectory(
                "MCP_SERVERS_DIR",
                directoryValue(mcpProperties == null || mcpProperties.getRegistry() == null
                        ? null
                        : mcpProperties.getRegistry().getExternalDir()),
                "mcp-servers-dir"
        );
    }

    private ResolvedPath resolveProvidersDir() {
        return resolveHostBackedDirectory("PROVIDERS_DIR", directoryValue(providerProperties == null ? null : providerProperties.getExternalDir()), "providers-dir");
    }

    private ResolvedPath resolveAgentSelfDir(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return null;
        }
        ResolvedPath agentsDir = resolveAgentsDir();
        if (agentsDir == null) {
            return null;
        }
        Path agentDir = Path.of(agentsDir.accessPath(), agentKey).toAbsolutePath().normalize();
        if (!Files.isDirectory(agentDir)) {
            return null;
        }
        return agentsDir.resolveChild(agentKey);
    }

    private ResolvedPath resolveAgentSkillsDir(String agentKey) {
        ResolvedPath agentSelfDir = resolveAgentSelfDir(agentKey);
        if (agentSelfDir == null) {
            return null;
        }
        ResolvedPath localSkillsDir = agentSelfDir.resolveChild("skills");
        Path localSkillsAccessPath = Path.of(localSkillsDir.accessPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(localSkillsAccessPath);
            return localSkillsDir.withAccessPath(localSkillsAccessPath.toString());
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for skills-dir: unable to prepare agent-local skills directory"
                            + " (resolved=" + localSkillsAccessPath + ", containerPath=" + SKILLS_PATH + ")",
                    ex
            );
        }
    }

    private ResolvedPath resolveOwnerDirPath() {
        ResolvedPath agentsDir = resolveAgentsDir();
        if (agentsDir == null) {
            return null;
        }
        Path agentsPath = Path.of(agentsDir.accessPath()).toAbsolutePath().normalize();
        Path parent = agentsPath.getParent();
        if (parent == null) {
            return null;
        }
        // OWNER_DIR is only the host mapping for the optional /owner platform mount.
        return resolveHostBackedDirectory("OWNER_DIR", parent.resolve("owner").toString(), "owner-dir");
    }

    private String resolveDirectory(String path) {
        if (StringUtils.hasText(path)) {
            return path.trim();
        }
        return null;
    }

    private String directoryValue(String path) {
        return StringUtils.hasText(path) ? path.trim() : null;
    }

    private ResolvedPath resolveHostBackedDirectory(String runtimeDirKey, String configuredPath, String mountName) {
        String hostPath = resolveDirectory(hostPaths.get(runtimeDirKey));
        String resolved = resolveDirectory(configuredPath);
        if (!StringUtils.hasText(hostPath) && !StringUtils.hasText(resolved)) {
            return null;
        }
        if (!StringUtils.hasText(hostPath) && looksLikeContainerInternalPath(resolved)) {
            throw new IllegalStateException(
                    ("container-hub mount validation failed for %s: missing %s in %s "
                            + "(configured=%s). Sandbox mounts must use the original host filesystem path.")
                            .formatted(mountName, runtimeDirKey, hostPaths.sourcePath(), resolved)
            );
        }
        String accessPath = StringUtils.hasText(resolved) ? resolved.trim() : hostPath.trim();
        String mountSourcePath = StringUtils.hasText(hostPath) ? hostPath.trim() : accessPath;
        String rawPath = StringUtils.hasText(resolved) ? resolved.trim() : mountSourcePath;
        return new ResolvedPath(normalizeRawPath(rawPath), accessPath, mountSourcePath);
    }

    private ResolvedPath resolveDirectPath(String configuredPath) {
        String resolved = resolveDirectory(configuredPath);
        if (!StringUtils.hasText(resolved)) {
            return null;
        }
        return new ResolvedPath(normalizeRawPath(resolved), resolved.trim(), resolved.trim());
    }

    private boolean looksLikeContainerInternalPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = Path.of(path.trim()).normalize().toString();
        return normalized.startsWith("/opt/");
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
        ResolvedPath source = platformMountDef.sourceSupplier().get();
        if (source == null) {
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
        ResolvedPath source = resolveDirectPath(extraMount.source());
        addResolvedMount(mountsByContainerPath, "extra-mount", source, normalizedDestination, extraMount.mode().readOnly());
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
                Map.entry("owner", new PlatformMountDef(this::resolveOwnerDirPath, "/owner", MountSourceType.DIRECTORY))
        );
    }

    private String normalizePlatform(String rawPlatform) {
        return rawPlatform == null ? "" : rawPlatform.trim().toLowerCase(Locale.ROOT);
    }

    private void addResolvedMount(
            Map<String, MountSpec> mountsByContainerPath,
            String mountName,
            ResolvedPath path,
            String containerPath,
            boolean readOnly
    ) {
        addResolvedMount(mountsByContainerPath, mountName, path, containerPath, readOnly, MountSourceType.DIRECTORY);
    }

    private void addResolvedMount(
            Map<String, MountSpec> mountsByContainerPath,
            String mountName,
            ResolvedPath path,
            String containerPath,
            boolean readOnly,
            MountSourceType sourceType
    ) {
        validateContainerPathConflict(mountsByContainerPath.keySet(), mountName, containerPath);
        validateExistingPath(mountName, path.rawPath(), path.accessPath(), containerPath, sourceType);
        addMount(mountsByContainerPath, new MountSpec(
                mountName,
                path.rawPath(),
                normalizeMountSourcePath(path.mountSourcePath()),
                containerPath,
                readOnly
        ));
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

    private String validateExistingPath(
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

    private String prepareRunDataMountDirectory(ResolvedPath dataDir, String chatId) {
        Path path = Path.of(dataDir.accessPath(), chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/workspace)".formatted(
                            dataDir.rawPath(),
                            path
                    ),
                    ex
            );
        }
        return normalizeMountSourcePath(Path.of(dataDir.mountSourcePath(), chatId).normalize().toString());
    }

    private void prepareSharedDataMountDirectory(ResolvedPath dataDir, String chatId) {
        Path path = Path.of(dataDir.accessPath(), chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/workspace/%s)".formatted(
                            dataDir.rawPath(),
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

    private String normalizeMountSourcePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return Path.of(path.trim()).normalize().toString();
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

    private record PlatformMountDef(Supplier<ResolvedPath> sourceSupplier, String containerPath, MountSourceType sourceType) {
    }

    private enum MountSourceType {
        DIRECTORY,
        FILE
    }

    private record ResolvedPath(String rawPath, String accessPath, String mountSourcePath) {

        private ResolvedPath {
            rawPath = rawPath == null ? "" : rawPath.trim();
            accessPath = accessPath == null ? "" : Path.of(accessPath.trim()).toAbsolutePath().normalize().toString();
            mountSourcePath = mountSourcePath == null ? "" : mountSourcePath.trim();
        }

        private ResolvedPath resolveChild(String child) {
            return new ResolvedPath(
                    Path.of(rawPath, child).normalize().toString(),
                    Path.of(accessPath, child).toAbsolutePath().normalize().toString(),
                    Path.of(mountSourcePath, child).normalize().toString()
            );
        }

        private ResolvedPath withAccessPath(String newAccessPath) {
            return new ResolvedPath(rawPath, newAccessPath, mountSourcePath);
        }
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath, boolean readOnly) {
    }
}
