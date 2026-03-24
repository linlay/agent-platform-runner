package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import com.linlay.agentplatform.service.mcp.McpServerRegistryService;
import com.linlay.agentplatform.service.mcp.McpToolSyncService;
import com.linlay.agentplatform.service.viewport.ViewportRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportServerRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportSyncService;
import com.linlay.agentplatform.config.properties.ToolProperties;
import com.linlay.agentplatform.config.properties.ViewportProperties;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.schedule.ScheduledQueryOrchestrator;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import com.linlay.agentplatform.util.CatalogDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class DirectoryWatchService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWatchService.class);
    private static final long DEBOUNCE_MS = 500;

    private final Map<Path, WatchedRoot> watchedRoots;
    private final Set<Path> registeredDirectories = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    private volatile WatchService watchService;
    private volatile Thread watchThread;

    @Autowired
    public DirectoryWatchService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            ToolFileRegistryService toolFileRegistryService,
            ModelRegistryService modelRegistryService,
            ProviderRegistryService providerRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpToolSyncService mcpToolSyncService,
            ViewportServerRegistryService viewportServerRegistryService,
            ViewportSyncService viewportSyncService,
            ScheduledQueryOrchestrator scheduledQueryOrchestrator,
            AgentProperties agentProperties,
            ViewportProperties viewportProperties,
            ToolProperties toolProperties,
            McpProperties mcpProperties,
            ViewportServerProperties viewportServerProperties,
            ModelProperties modelProperties,
            ProviderProperties providerProperties,
            SkillProperties skillProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties
    ) {
        this.watchedRoots = buildWatchedRoots(
                agentRegistry,
                viewportRegistryService,
                toolFileRegistryService,
                modelRegistryService,
                providerRegistryService,
                skillRegistryService,
                teamRegistryService,
                mcpServerRegistryService,
                mcpToolSyncService,
                viewportServerRegistryService,
                viewportSyncService,
                scheduledQueryOrchestrator,
                agentProperties,
                viewportProperties,
                toolProperties,
                mcpProperties,
                viewportServerProperties,
                modelProperties,
                providerProperties,
                skillProperties,
                teamProperties,
                scheduleProperties
        );
        start();
    }

    // visible for testing
    DirectoryWatchService(
            AgentRegistry agentRegistry,
            SkillRegistryService skillRegistryService,
            Map<Path, Runnable> watchedDirs
    ) {
        this.watchedRoots = testRoots(watchedDirs);
        start();
    }

    // visible for legacy tests
    DirectoryWatchService(
            AgentRegistry agentRegistry,
            ModelRegistryService modelRegistryService,
            ProviderRegistryService providerRegistryService,
            SkillRegistryService skillRegistryService,
            Map<Path, Runnable> watchedDirs
    ) {
        this(agentRegistry, skillRegistryService, watchedDirs);
    }

    private Map<Path, WatchedRoot> buildWatchedRoots(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            ToolFileRegistryService toolFileRegistryService,
            ModelRegistryService modelRegistryService,
            ProviderRegistryService providerRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpToolSyncService mcpToolSyncService,
            ViewportServerRegistryService viewportServerRegistryService,
            ViewportSyncService viewportSyncService,
            ScheduledQueryOrchestrator scheduledQueryOrchestrator,
            AgentProperties agentProperties,
            ViewportProperties viewportProperties,
            ToolProperties toolProperties,
            McpProperties mcpProperties,
            ViewportServerProperties viewportServerProperties,
            ModelProperties modelProperties,
            ProviderProperties providerProperties,
            SkillProperties skillProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties
    ) {
        Map<Path, WatchedRoot> roots = new LinkedHashMap<>();
        registerRoot(roots, RootKind.AGENTS, agentProperties.getExternalDir(), false,
                changedPath -> routeAgentChange(agentProperties.getExternalDir(), changedPath, agentRegistry));
        registerRoot(roots, RootKind.TEAMS, teamProperties.getExternalDir(), true,
                changedPath -> teamRegistryService.refreshTeams());
        registerRoot(roots, RootKind.MODELS, modelProperties.getExternalDir(), true,
                changedPath -> {
                    CatalogDiff diff = modelRegistryService.refreshModels();
                    if (diff.isEmpty()) {
                        return;
                    }
                    Set<String> affectedAgents = agentRegistry.findAgentIdsByModels(diff.changedKeys());
                    agentRegistry.refreshAgentsByIds(affectedAgents, "models-directory");
                });
        registerRoot(roots, RootKind.PROVIDERS, providerProperties.getExternalDir(), true,
                changedPath -> {
                    CatalogDiff providerDiff = providerRegistryService.refreshProviders();
                    if (providerDiff.isEmpty()) {
                        return;
                    }
                    Set<String> referencingModelKeys = modelRegistryService.findModelKeysByProviders(providerDiff.changedKeys());
                    CatalogDiff modelDiff = modelRegistryService.refreshModels();
                    Set<String> affectedModelKeys = new LinkedHashSet<>(referencingModelKeys);
                    affectedModelKeys.addAll(modelDiff.changedKeys());
                    if (affectedModelKeys.isEmpty()) {
                        return;
                    }
                    Set<String> affectedAgents = agentRegistry.findAgentIdsByModels(affectedModelKeys);
                    agentRegistry.refreshAgentsByIds(affectedAgents, "providers-directory");
                });
        registerRoot(roots, RootKind.MCP_SERVERS, mcpProperties.getRegistry().getExternalDir(), true,
                changedPath -> {
                    mcpServerRegistryService.refreshServers();
                    CatalogDiff diff = mcpToolSyncService.refreshTools();
                    if (diff.isEmpty()) {
                        return;
                    }
                    Set<String> affectedAgents = agentRegistry.findAgentIdsByTools(diff.changedKeys());
                    agentRegistry.refreshAgentsByIds(affectedAgents, "mcp-registry-directory");
                });
        registerRoot(roots, RootKind.VIEWPORT_SERVERS, viewportServerProperties.getRegistry().getExternalDir(), true,
                changedPath -> {
                    viewportServerRegistryService.refreshServers();
                    viewportSyncService.refreshViewports();
                });
        registerRoot(roots, RootKind.SCHEDULES, scheduleProperties.getExternalDir(), true,
                changedPath -> scheduledQueryOrchestrator.refreshAndReconcile());
        return Map.copyOf(roots);
    }

    private Map<Path, WatchedRoot> testRoots(Map<Path, Runnable> watchedDirs) {
        Map<Path, WatchedRoot> roots = new LinkedHashMap<>();
        if (watchedDirs == null || watchedDirs.isEmpty()) {
            return Map.of();
        }
        for (Map.Entry<Path, Runnable> entry : watchedDirs.entrySet()) {
            Path root = normalizePath(entry.getKey());
            if (root == null) {
                continue;
            }
            Runnable action = entry.getValue();
            roots.put(root, new WatchedRoot(RootKind.TEST, root, true, ignored -> {
                if (action != null) {
                    action.run();
                }
            }));
        }
        return Map.copyOf(roots);
    }

    private void registerRoot(
            Map<Path, WatchedRoot> roots,
            RootKind kind,
            String rawPath,
            boolean recursive,
            Consumer<Path> action
    ) {
        Path root = normalizePath(rawPath == null ? null : Path.of(rawPath));
        if (root == null || action == null) {
            return;
        }
        roots.put(root, new WatchedRoot(kind, root, recursive, action));
    }

    private void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            log.warn("Cannot create WatchService, directory watching disabled", ex);
            return;
        }

        Map<WatchKey, RegisteredDirectory> keyRegistrations = new LinkedHashMap<>();
        Map<Path, WatchKey> pathRegistrations = new LinkedHashMap<>();
        Map<Path, Integer> registeredDirCounts = new LinkedHashMap<>();
        List<WatchedRoot> skippedRoots = new java.util.ArrayList<>();
        for (WatchedRoot root : watchedRoots.values()) {
            if (!Files.isDirectory(root.path())) {
                skippedRoots.add(root);
                continue;
            }
            if (root.kind() == RootKind.AGENTS) {
                registerAgentDirectories(root, keyRegistrations, pathRegistrations, registeredDirCounts);
            } else if (root.recursive()) {
                registerDirectoryTree(root.path(), root, keyRegistrations, pathRegistrations, registeredDirCounts);
            } else {
                registerDirectory(root.path(), root, keyRegistrations, pathRegistrations, registeredDirCounts);
            }
        }

        if (keyRegistrations.isEmpty()) {
            logWatchedRootSummary(registeredDirCounts, skippedRoots);
            closeWatchService();
            return;
        }

        logWatchedRootSummary(registeredDirCounts, skippedRoots);
        watchThread = new Thread(() -> pollLoop(keyRegistrations, pathRegistrations), "dir-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void pollLoop(
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations
    ) {
        Map<Path, Long> lastTrigger = new LinkedHashMap<>();

        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.poll(2, TimeUnit.SECONDS);
            } catch (ClosedWatchServiceException | InterruptedException ex) {
                break;
            }
            if (key == null) {
                continue;
            }

            RegisteredDirectory registration = keyRegistrations.get(key);
            if (registration == null) {
                key.reset();
                continue;
            }

            Path changedPath = processEvents(registration, key.pollEvents(), keyRegistrations, pathRegistrations);
            if (changedPath != null) {
                WatchedRoot root = registration.root();
                long now = System.currentTimeMillis();
                Long last = lastTrigger.get(root.path());
                if (last == null || now - last >= DEBOUNCE_MS) {
                    lastTrigger.put(root.path(), now);
                    try {
                        root.action().accept(changedPath);
                    } catch (Exception ex) {
                        log.warn("Error executing refresh callback for {}", root.kind(), ex);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                RegisteredDirectory removed = keyRegistrations.remove(key);
                if (removed != null) {
                    pathRegistrations.remove(removed.dir());
                    registeredDirectories.remove(removed.dir());
                    lastTrigger.remove(removed.root().path());
                }
                if (keyRegistrations.isEmpty()) {
                    break;
                }
            }
        }
    }

    private Path processEvents(
            RegisteredDirectory registration,
            List<WatchEvent<?>> events,
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations
    ) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        WatchedRoot root = registration.root();
        Path changedPath = null;
        for (WatchEvent<?> event : events) {
            if (event == null) {
                continue;
            }
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                changedPath = root.path();
                continue;
            }
            Object context = event.context();
            if (!(context instanceof Path relativePath)) {
                continue;
            }
            Path absoluteChangedPath = registration.dir().resolve(relativePath).toAbsolutePath().normalize();
            if (kind == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(absoluteChangedPath)
                    && !containsHiddenPathSegment(root.path(), absoluteChangedPath)) {
                if (root.kind() == RootKind.AGENTS) {
                    registerAgentDirectoryIfNeeded(
                            root,
                            absoluteChangedPath,
                            keyRegistrations,
                            pathRegistrations,
                            new LinkedHashMap<>()
                    );
                } else if (root.recursive()) {
                    registerDirectoryTree(
                            absoluteChangedPath,
                            root,
                            keyRegistrations,
                            pathRegistrations,
                            new LinkedHashMap<>()
                    );
                }
            }
            if (containsHiddenPathSegment(root.path(), absoluteChangedPath)) {
                continue;
            }
            if (changedPath == null) {
                changedPath = absoluteChangedPath;
            }
        }
        return changedPath;
    }

    private void registerAgentDirectories(
            WatchedRoot root,
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations,
            Map<Path, Integer> registeredDirCounts
    ) {
        registerDirectory(root.path(), root, keyRegistrations, pathRegistrations, registeredDirCounts);
        try (var stream = Files.list(root.path())) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !containsHiddenPathSegment(root.path(), path))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> registerDirectory(path, root, keyRegistrations, pathRegistrations, registeredDirCounts));
        } catch (IOException ex) {
            log.warn("Cannot register agent watch directories on {}", root.path(), ex);
        }
    }

    private void registerAgentDirectoryIfNeeded(
            WatchedRoot root,
            Path candidateDir,
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations,
            Map<Path, Integer> registeredDirCounts
    ) {
        if (root == null || candidateDir == null || !Files.isDirectory(candidateDir)) {
            return;
        }
        Path relative = root.path().relativize(candidateDir.toAbsolutePath().normalize());
        if (relative.getNameCount() != 1) {
            return;
        }
        registerDirectory(candidateDir, root, keyRegistrations, pathRegistrations, registeredDirCounts);
    }

    private void registerDirectoryTree(
            Path rootDir,
            WatchedRoot root,
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations,
            Map<Path, Integer> registeredDirCounts
    ) {
        try (var stream = Files.walk(rootDir)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> !containsHiddenPathSegment(root.path(), path))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> registerDirectory(path, root, keyRegistrations, pathRegistrations, registeredDirCounts));
        } catch (IOException ex) {
            log.warn("Cannot register recursive watch on {}", rootDir, ex);
        }
    }

    private void registerDirectory(
            Path dir,
            WatchedRoot root,
            Map<WatchKey, RegisteredDirectory> keyRegistrations,
            Map<Path, WatchKey> pathRegistrations,
            Map<Path, Integer> registeredDirCounts
    ) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        Path normalizedDir = dir.toAbsolutePath().normalize();
        if (pathRegistrations.containsKey(normalizedDir)) {
            return;
        }
        try {
            WatchKey key = normalizedDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            pathRegistrations.put(normalizedDir, key);
            keyRegistrations.put(key, new RegisteredDirectory(normalizedDir, root));
            registeredDirectories.add(normalizedDir);
            registeredDirCounts.merge(root.path(), 1, Integer::sum);
            log.debug("Watching directory{}: {}", root.recursive() ? " recursively" : "", normalizedDir);
        } catch (IOException ex) {
            log.warn("Cannot register watch on {}", normalizedDir, ex);
        }
    }

    private void routeAgentChange(String agentsDir, Path changedPath, AgentRegistry agentRegistry) {
        Path root = normalizePath(agentsDir == null ? null : Path.of(agentsDir));
        Path normalizedChangedPath = normalizePath(changedPath);
        if (root == null || normalizedChangedPath == null || agentRegistry == null) {
            return;
        }
        if (!normalizedChangedPath.startsWith(root)) {
            agentRegistry.refreshAgents();
            return;
        }
        if (root.equals(normalizedChangedPath)) {
            agentRegistry.refreshAgents();
            return;
        }
        if (containsHiddenPathSegment(root, normalizedChangedPath)) {
            return;
        }

        Path relative = root.relativize(normalizedChangedPath);
        if (relative.getNameCount() == 0) {
            agentRegistry.refreshAgents();
            return;
        }

        String agentId = relative.getName(0).toString().trim();
        if (!StringUtils.hasText(agentId) || agentId.startsWith(".")) {
            return;
        }
        if (relative.getNameCount() == 1) {
            agentRegistry.refreshAgents();
            return;
        }

        String topLevel = relative.getName(1).toString().trim();
        if ("memory".equals(topLevel)
                || "tools".equals(topLevel)
                || "skills".equals(topLevel)) {
            return;
        }

        String fileName = normalizedChangedPath.getFileName() == null ? "" : normalizedChangedPath.getFileName().toString();
        if (relative.getNameCount() == 2 && (isAgentDefinitionFile(fileName) || isAgentPromptMarkdown(fileName))) {
            agentRegistry.refreshAgentsByIds(Set.of(agentId), "agents-directory");
        }
    }

    private boolean isAgentDefinitionFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        return "agent.yml".equals(lower) || "agent.yaml".equals(lower);
    }

    private boolean isAgentPromptMarkdown(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return "soul.md".equals(lower)
                || "agents.md".equals(lower)
                || "agents.plan.md".equals(lower)
                || "agents.execute.md".equals(lower)
                || "agents.summary.md".equals(lower)
                || "agents.plain.md".equals(lower)
                || "agents.react.md".equals(lower);
    }

    private boolean containsHiddenPathSegment(Path root, Path path) {
        if (root == null || path == null) {
            return false;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return false;
        }
        Path relative = normalizedRoot.relativize(normalizedPath);
        for (Path segment : relative) {
            if (segment != null) {
                String value = segment.toString();
                if (StringUtils.hasText(value) && value.startsWith(".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Path normalizePath(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private void logWatchedRootSummary(Map<Path, Integer> registeredDirCounts, List<WatchedRoot> skippedRoots) {
        if (registeredDirCounts != null && !registeredDirCounts.isEmpty()) {
            watchedRoots.values().stream()
                    .filter(root -> registeredDirCounts.containsKey(root.path()))
                    .sorted(Comparator
                            .comparingInt((WatchedRoot root) -> logOrder(root.kind()))
                            .thenComparing(root -> root.path().toString()))
                    .forEach(root -> log.info(
                            "Directory watch root active: {}={} (dirs={})",
                            root.kind(),
                            root.path(),
                            registeredDirCounts.getOrDefault(root.path(), 0)
                    ));
        } else {
            log.info("Directory watch roots active: none");
        }
        if (skippedRoots != null && !skippedRoots.isEmpty()) {
            skippedRoots.stream()
                    .sorted(Comparator
                            .comparingInt((WatchedRoot root) -> logOrder(root.kind()))
                            .thenComparing(root -> root.path().toString()))
                    .forEach(root -> log.warn(
                            "Directory watch root skipped: {}={} (reason=missing)",
                            root.kind(),
                            root.path()
                    ));
        }
    }

    private int logOrder(RootKind kind) {
        return switch (kind) {
            case AGENTS -> 0;
            case MODELS -> 1;
            case PROVIDERS -> 2;
            case MCP_SERVERS -> 3;
            case VIEWPORT_SERVERS -> 4;
            case TEAMS -> 5;
            case SCHEDULES -> 6;
            case SKILLS_MARKET -> 7;
            case TOOLS -> 8;
            case VIEWPORTS -> 9;
            case TEST -> 10;
        };
    }

    Set<Path> watchedRootPathsForTesting() {
        return watchedRoots.keySet();
    }

    Set<Path> registeredDirectoriesForTesting() {
        synchronized (registeredDirectories) {
            return Set.copyOf(registeredDirectories);
        }
    }

    void triggerForTesting(Path watchedRootPath, Path changedPath) {
        Path normalizedRoot = normalizePath(watchedRootPath);
        WatchedRoot root = watchedRoots.get(normalizedRoot);
        if (root != null) {
            root.action().accept(normalizePath(changedPath == null ? normalizedRoot : changedPath));
        }
    }

    private void closeWatchService() {
        WatchService ws = watchService;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ex) {
                log.debug("Error closing WatchService", ex);
            }
        }
    }

    @Override
    public void destroy() {
        closeWatchService();
        Thread t = watchThread;
        if (t != null) {
            t.interrupt();
        }
    }

    enum RootKind {
        AGENTS,
        SKILLS_MARKET,
        TEAMS,
        MODELS,
        PROVIDERS,
        MCP_SERVERS,
        VIEWPORT_SERVERS,
        SCHEDULES,
        TOOLS,
        VIEWPORTS,
        TEST
    }

    record WatchedRoot(
            RootKind kind,
            Path path,
            boolean recursive,
            Consumer<Path> action
    ) {
    }

    private record RegisteredDirectory(
            Path dir,
            WatchedRoot root
    ) {
    }
}
