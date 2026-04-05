package com.linlay.agentplatform.integration.viewport;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.integration.remoteserver.AbstractReconnectOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ViewportReconnectOrchestrator extends AbstractReconnectOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ViewportReconnectOrchestrator.class);

    private final ViewportServerProperties properties;
    private final ViewportServerRegistryService serverRegistryService;
    private final ViewportServerAvailabilityGate availabilityGate;
    private final ViewportSyncService viewportSyncService;

    public ViewportReconnectOrchestrator(
            ViewportServerProperties properties,
            ViewportServerRegistryService serverRegistryService,
            ViewportServerAvailabilityGate availabilityGate,
            ViewportSyncService viewportSyncService,
            @Qualifier("mcpReconnectTaskScheduler") TaskScheduler taskScheduler
    ) {
        super(taskScheduler);
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.viewportSyncService = viewportSyncService;
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
                        .map(ViewportServerRegistryService.RegisteredServer::serverKey)
                        .toList()
        );
        if (dueServerKeys.isEmpty()) {
            return;
        }
        try {
            viewportSyncService.refreshViewportsForServers(dueServerKeys);
        } catch (Exception ex) {
            log.warn("Error retrying unavailable viewport servers: {}", dueServerKeys, ex);
        }
    }
}
