package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.AgentCatalogProperties;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.CapabilityCatalogProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ViewportCatalogProperties;
import com.linlay.agentplatform.model.ModelCatalogProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.schedule.ScheduleCatalogProperties;
import com.linlay.agentplatform.schedule.ScheduledQueryOrchestrator;
import com.linlay.agentplatform.skill.SkillCatalogProperties;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamCatalogProperties;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.CapabilityRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DirectoryWatchService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWatchService.class);
    private static final long DEBOUNCE_MS = 500;

    private final Map<Path, Runnable> watchedDirs;

    private volatile WatchService watchService;
    private volatile Thread watchThread;

    @Autowired
    public DirectoryWatchService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            CapabilityRegistryService capabilityRegistryService,
            ModelRegistryService modelRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpCapabilitySyncService mcpCapabilitySyncService,
            ScheduledQueryOrchestrator scheduledQueryOrchestrator,
            AgentCatalogProperties agentCatalogProperties,
            ViewportCatalogProperties viewportCatalogProperties,
            CapabilityCatalogProperties capabilityCatalogProperties,
            McpProperties mcpProperties,
            ModelCatalogProperties modelCatalogProperties,
            SkillCatalogProperties skillCatalogProperties,
            TeamCatalogProperties teamCatalogProperties,
            ScheduleCatalogProperties scheduleCatalogProperties
    ) {
        this.watchedDirs = new LinkedHashMap<>();
        watchedDirs.put(
                Path.of(agentCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                agentRegistry::refreshAgents
        );
        watchedDirs.put(
                Path.of(viewportCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                viewportRegistryService::refreshViewports
        );
        watchedDirs.put(
                Path.of(capabilityCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                () -> {
                    CatalogDiff diff = capabilityRegistryService.refreshCapabilities();
                    if (diff.isEmpty()) {
                        return;
                    }
                    java.util.Set<String> affectedAgents = agentRegistry.findAgentIdsByTools(diff.changedKeys());
                    agentRegistry.refreshAgentsByIds(affectedAgents, "tools-directory");
                }
        );
        watchedDirs.put(
                Path.of(skillCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                skillRegistryService::refreshSkills
        );
        watchedDirs.put(
                Path.of(teamCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                teamRegistryService::refreshTeams
        );
        watchedDirs.put(
                Path.of(modelCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                () -> {
                    CatalogDiff diff = modelRegistryService.refreshModels();
                    if (diff.isEmpty()) {
                        return;
                    }
                    java.util.Set<String> affectedAgents = agentRegistry.findAgentIdsByModels(diff.changedKeys());
                    agentRegistry.refreshAgentsByIds(affectedAgents, "models-directory");
                }
        );
        watchedDirs.put(
                Path.of(mcpProperties.getRegistry().getExternalDir()).toAbsolutePath().normalize(),
                () -> {
                    mcpServerRegistryService.refreshServers();
                    CatalogDiff diff = mcpCapabilitySyncService.refreshCapabilities();
                    if (diff.isEmpty()) {
                        return;
                    }
                    java.util.Set<String> affectedAgents = agentRegistry.findAgentIdsByTools(diff.changedKeys());
                    agentRegistry.refreshAgentsByIds(affectedAgents, "mcp-registry-directory");
                }
        );
        watchedDirs.put(
                Path.of(scheduleCatalogProperties.getExternalDir()).toAbsolutePath().normalize(),
                scheduledQueryOrchestrator::refreshAndReconcile
        );

        start();
    }

    // visible for testing
    DirectoryWatchService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            CapabilityRegistryService capabilityRegistryService,
            SkillRegistryService skillRegistryService,
            Map<Path, Runnable> watchedDirs
    ) {
        this.watchedDirs = watchedDirs;
        start();
    }

    private void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            log.warn("Cannot create WatchService, directory watching disabled", ex);
            return;
        }

        Map<WatchKey, Runnable> keyActions = new LinkedHashMap<>();
        for (Map.Entry<Path, Runnable> entry : watchedDirs.entrySet()) {
            Path dir = entry.getKey();
            if (!Files.isDirectory(dir)) {
                log.debug("Directory does not exist, skip watching: {}", dir);
                continue;
            }
            try {
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                keyActions.put(key, entry.getValue());
                log.info("Watching directory: {}", dir);
            } catch (IOException ex) {
                log.warn("Cannot register watch on {}", dir, ex);
            }
        }

        if (keyActions.isEmpty()) {
            closeWatchService();
            return;
        }

        watchThread = new Thread(() -> pollLoop(keyActions), "dir-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void pollLoop(Map<WatchKey, Runnable> keyActions) {
        Map<WatchKey, Long> lastTrigger = new LinkedHashMap<>();

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

            Runnable action = keyActions.get(key);
            boolean hasRelevantEvent = hasRelevantEvents(key.pollEvents());

            if (action != null && hasRelevantEvent) {
                long now = System.currentTimeMillis();
                Long last = lastTrigger.get(key);
                if (last == null || now - last >= DEBOUNCE_MS) {
                    lastTrigger.put(key, now);
                    try {
                        action.run();
                    } catch (Exception ex) {
                        log.warn("Error executing refresh callback", ex);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                keyActions.remove(key);
                lastTrigger.remove(key);
                if (keyActions.isEmpty()) {
                    break;
                }
            }
        }
    }

    private boolean hasRelevantEvents(List<WatchEvent<?>> events) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        for (WatchEvent<?> event : events) {
            if (event == null) {
                continue;
            }
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.ENTRY_CREATE
                    || kind == StandardWatchEventKinds.ENTRY_MODIFY
                    || kind == StandardWatchEventKinds.ENTRY_DELETE
                    || kind == StandardWatchEventKinds.OVERFLOW) {
                return true;
            }
        }
        return false;
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
}
