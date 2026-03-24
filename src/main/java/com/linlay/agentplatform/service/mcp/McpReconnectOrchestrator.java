package com.linlay.agentplatform.service.mcp;

import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.util.CatalogDiff;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

@Service
public class McpReconnectOrchestrator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpReconnectOrchestrator.class);

    private final AgentRegistry agentRegistry;
    private final McpProperties properties;
    private final McpServerRegistryService serverRegistryService;
    private final McpServerAvailabilityGate availabilityGate;
    private final McpToolSyncService mcpToolSyncService;
    private final TaskScheduler taskScheduler;

    private final Object lifecycleLock = new Object();
    private volatile ScheduledFuture<?> future;

    public McpReconnectOrchestrator(
            AgentRegistry agentRegistry,
            McpProperties properties,
            McpServerRegistryService serverRegistryService,
            McpServerAvailabilityGate availabilityGate,
            McpToolSyncService mcpToolSyncService,
            @Qualifier("mcpReconnectTaskScheduler") TaskScheduler taskScheduler
    ) {
        this.agentRegistry = agentRegistry;
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.mcpToolSyncService = mcpToolSyncService;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void initialize() {
        synchronized (lifecycleLock) {
            if (future != null) {
                return;
            }
            long intervalMs = Math.max(1L, properties.getReconnectIntervalMs());
            future = taskScheduler.scheduleWithFixedDelay(this::retryDueServers, Duration.ofMillis(intervalMs));
        }
    }

    void retryDueServers() {
        if (!properties.isEnabled()) {
            return;
        }
        Set<String> dueServerKeys = availabilityGate.readyToRetry(
                serverRegistryService.list().stream()
                        .map(McpServerRegistryService.RegisteredServer::serverKey)
                        .toList()
        );
        if (dueServerKeys.isEmpty()) {
            return;
        }
        try {
            CatalogDiff diff = mcpToolSyncService.refreshToolsForServers(dueServerKeys);
            if (diff.isEmpty()) {
                return;
            }
            Set<String> affectedAgents = agentRegistry.findAgentIdsByTools(diff.changedKeys());
            agentRegistry.refreshAgentsByIds(affectedAgents, "mcp-reconnect");
        } catch (Exception ex) {
            log.warn("Error retrying unavailable MCP servers: {}", dueServerKeys, ex);
        }
    }

    @Override
    public void destroy() {
        synchronized (lifecycleLock) {
            if (future == null) {
                return;
            }
            future.cancel(false);
            future = null;
        }
    }
}
