package com.linlay.agentplatform.integration.mcp;

import com.linlay.agentplatform.engine.definition.AgentRegistry;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.integration.remoteserver.AbstractReconnectOrchestrator;
import com.linlay.agentplatform.util.CatalogDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class McpReconnectOrchestrator extends AbstractReconnectOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(McpReconnectOrchestrator.class);

    private final AgentRegistry agentRegistry;
    private final McpProperties properties;
    private final McpServerRegistryService serverRegistryService;
    private final McpServerAvailabilityGate availabilityGate;
    private final McpToolSyncService mcpToolSyncService;

    public McpReconnectOrchestrator(
            AgentRegistry agentRegistry,
            McpProperties properties,
            McpServerRegistryService serverRegistryService,
            McpServerAvailabilityGate availabilityGate,
            McpToolSyncService mcpToolSyncService,
            @Qualifier("mcpReconnectTaskScheduler") TaskScheduler taskScheduler
    ) {
        super(taskScheduler);
        this.agentRegistry = agentRegistry;
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.mcpToolSyncService = mcpToolSyncService;
    }

    @Override
    protected boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    protected long getReconnectIntervalMs() {
        return properties.getReconnectIntervalMs();
    }

    @Override
    protected void retryDueServers() {
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
}
