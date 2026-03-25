package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
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
    private static final MountDirectoryConfig EMPTY_DIRECTORIES = new MountDirectoryConfig(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );
    private static final Set<String> DEFAULT_OVERRIDEABLE_PATHS = Set.of(
            WORKSPACE_PATH,
            ROOT_PATH,
            SKILLS_PATH,
            PAN_PATH,
            AGENT_PATH
    );

    private final MountDirectoryConfig directories;
    private final RuntimeDirectoryHostPaths hostPaths;

    public ContainerHubMountResolver(MountDirectoryConfig directories, RuntimeDirectoryHostPaths hostPaths) {
        this.directories = directories == null ? EMPTY_DIRECTORIES : directories;
        this.hostPaths = hostPaths == null ? new RuntimeDirectoryHostPaths(Map.of()) : hostPaths;
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
                        dataDir.configuredPath(),
                        dataDir.localPath(),
                        WORKSPACE_PATH,
                        MountSourceType.DIRECTORY
                );
                if (StringUtils.hasText(chatId)) {
                    prepareSharedDataMountDirectory(dataDir, chatId);
                }
                mountSourcePath = normalizeMountSourcePath(dataDir.hostPath());
            }
            addMount(
                    mountsByContainerPath,
                    new MountSpec("data-dir", dataDir.configuredPath(), mountSourcePath, WORKSPACE_PATH, false)
            );
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
        return resolveHostBackedDirectory("CHATS_DIR", directories.chatsDir(), "data-dir");
    }

    private ResolvedPath resolveRootDir() {
        return resolveHostBackedDirectory("ROOT_DIR", directories.rootDir(), "root-dir");
    }

    private ResolvedPath resolveGlobalSkillsDir() {
        return resolveHostBackedDirectory("SKILLS_MARKET_DIR", directories.skillsDir(), "skills-dir");
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
        return resolveHostBackedDirectory("PAN_DIR", directories.panDir(), "pan-dir");
    }

    private ResolvedPath resolveToolsDir() {
        return resolveDirectPath(directories.toolsDir());
    }

    private ResolvedPath resolveChatsDir() {
        return resolveDataDir();
    }

    private ResolvedPath resolveAgentsDir() {
        return resolveHostBackedDirectory("AGENTS_DIR", directories.agentsDir(), "agents-dir");
    }

    private ResolvedPath resolveRegistriesDir() {
        return resolveHostBackedDirectory("REGISTRIES_DIR", directories.registriesDir(), "registries-dir");
    }

    private ResolvedPath resolveModelsDir() {
        return resolveRegistryChild(resolveRegistriesDir(), "models");
    }

    private ResolvedPath resolveViewportsDir() {
        return resolveDirectPath(directories.viewportsDir());
    }

    private ResolvedPath resolveViewportServersDir() {
        return resolveRegistryChild(resolveRegistriesDir(), "viewport-servers");
    }

    private ResolvedPath resolveTeamsDir() {
        return resolveHostBackedDirectory("TEAMS_DIR", directories.teamsDir(), "teams-dir");
    }

    private ResolvedPath resolveSchedulesDir() {
        return resolveHostBackedDirectory("SCHEDULES_DIR", directories.schedulesDir(), "schedules-dir");
    }

    private ResolvedPath resolveMcpServersDir() {
        return resolveRegistryChild(resolveRegistriesDir(), "mcp-servers");
    }

    private ResolvedPath resolveProvidersDir() {
        return resolveRegistryChild(resolveRegistriesDir(), "providers");
    }

    private ResolvedPath resolveOwnerDirPath() {
        return resolveHostBackedDirectory("OWNER_DIR", directories.ownerDir(), "owner-dir");
    }

    private ResolvedPath resolveRegistryChild(ResolvedPath registriesDir, String child) {
        if (registriesDir == null) {
            return null;
        }
        return registriesDir.resolveChild(child);
    }

    private ResolvedPath resolveAgentSelfDir(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return null;
        }
        ResolvedPath agentsDir = resolveAgentsDir();
        if (agentsDir == null) {
            return null;
        }
        Path agentDir = Path.of(agentsDir.localPath(), agentKey).toAbsolutePath().normalize();
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
        Path localSkillsAccessPath = Path.of(localSkillsDir.localPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(localSkillsAccessPath);
            return localSkillsDir.withLocalPath(localSkillsAccessPath.toString());
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for skills-dir: unable to prepare agent-local skills directory"
                            + " (resolved=" + localSkillsAccessPath + ", containerPath=" + SKILLS_PATH + ")",
                    ex
            );
        }
    }

    private String directoryValue(String path) {
        return StringUtils.hasText(path) ? path.trim() : null;
    }

    private ResolvedPath resolveHostBackedDirectory(String runtimeDirKey, String configuredPath, String mountName) {
        String envHostPath = directoryValue(hostPaths.get(runtimeDirKey));
        String configured = directoryValue(configuredPath);
        if (configured == null && envHostPath == null) {
            return null;
        }

        String localPath = configured != null ? configured : envHostPath;
        String hostPath = envHostPath != null ? envHostPath : localPath;
        String configuredForMessage = configured != null ? configured : localPath;

        // If Docker rewrote the local path to /opt/*, sandbox mounts still need the original host path.
        if (hostPath.equals(localPath) && looksLikeContainerInternalPath(localPath)) {
            throw new IllegalStateException(
                    ("container-hub mount validation failed for %s: missing %s in %s "
                            + "(configured=%s). Sandbox mounts must use the original host filesystem path.")
                            .formatted(mountName, runtimeDirKey, hostPaths.sourcePath(), configuredForMessage)
            );
        }

        return new ResolvedPath(configuredForMessage, localPath, hostPath);
    }

    private ResolvedPath resolveDirectPath(String configuredPath) {
        String configured = directoryValue(configuredPath);
        if (configured == null) {
            return null;
        }
        return new ResolvedPath(configured, configured, configured);
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
        validateExistingPath(mountName, path.configuredPath(), path.localPath(), containerPath, sourceType);
        addMount(
                mountsByContainerPath,
                new MountSpec(
                        mountName,
                        path.configuredPath(),
                        normalizeMountSourcePath(path.hostPath()),
                        containerPath,
                        readOnly
                )
        );
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

    private void validateExistingPath(
            String mountName,
            String configuredPath,
            String localPath,
            String containerPath,
            MountSourceType sourceType
    ) {
        Path path = Path.of(localPath);
        if (sourceType == MountSourceType.DIRECTORY && Files.isDirectory(path)) {
            return;
        }
        if (sourceType == MountSourceType.FILE && Files.isRegularFile(path)) {
            return;
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
                .formatted(mountName, reason, normalizeConfiguredPath(configuredPath), path.toAbsolutePath().normalize(), containerPath));
    }

    private String prepareRunDataMountDirectory(ResolvedPath dataDir, String chatId) {
        Path path = Path.of(dataDir.localPath(), chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/workspace)".formatted(
                            dataDir.configuredPath(),
                            path
                    ),
                    ex
            );
        }
        return normalizeMountSourcePath(Path.of(dataDir.hostPath(), chatId).normalize().toString());
    }

    private void prepareSharedDataMountDirectory(ResolvedPath dataDir, String chatId) {
        Path path = Path.of(dataDir.localPath(), chatId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "container-hub mount validation failed for data-dir: failed to prepare chat-scoped directory "
                            + "(configured=%s, resolved=%s, containerPath=/workspace/%s)".formatted(
                            dataDir.configuredPath(),
                            path,
                            chatId.trim()
                    ),
                    ex
            );
        }
    }

    private String normalizeConfiguredPath(String configuredPath) {
        return configuredPath == null ? "" : configuredPath.trim();
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

    private record PlatformMountDef(Supplier<ResolvedPath> sourceSupplier, String containerPath, MountSourceType sourceType) {
    }

    private enum MountSourceType {
        DIRECTORY,
        FILE
    }

    private record ResolvedPath(String configuredPath, String localPath, String hostPath) {

        private ResolvedPath {
            configuredPath = configuredPath == null ? "" : configuredPath.trim();
            localPath = localPath == null ? "" : Path.of(localPath.trim()).toAbsolutePath().normalize().toString();
            hostPath = hostPath == null ? "" : hostPath.trim();
        }

        private ResolvedPath resolveChild(String child) {
            return new ResolvedPath(
                    Path.of(configuredPath, child).normalize().toString(),
                    Path.of(localPath, child).toAbsolutePath().normalize().toString(),
                    Path.of(hostPath, child).normalize().toString()
            );
        }

        private ResolvedPath withLocalPath(String newLocalPath) {
            return new ResolvedPath(configuredPath, newLocalPath, hostPath);
        }
    }

    public record MountSpec(String mountName, String rawPath, String hostPath, String containerPath, boolean readOnly) {
    }
}
