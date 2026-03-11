package com.linlay.agentplatform.service;

import com.linlay.agentplatform.config.ViewportServerProperties;
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
public class ViewportReconnectOrchestrator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ViewportReconnectOrchestrator.class);

    private final ViewportServerProperties properties;
    private final ViewportServerRegistryService serverRegistryService;
    private final ViewportServerAvailabilityGate availabilityGate;
    private final ViewportSyncService viewportSyncService;

    private final TaskScheduler taskScheduler;
    private final Object lifecycleLock = new Object();
    private volatile ScheduledFuture<?> future;

    public ViewportReconnectOrchestrator(
            ViewportServerProperties properties,
            ViewportServerRegistryService serverRegistryService,
            ViewportServerAvailabilityGate availabilityGate,
            ViewportSyncService viewportSyncService,
            @Qualifier("mcpReconnectTaskScheduler") TaskScheduler taskScheduler
    ) {
        this.properties = properties;
        this.serverRegistryService = serverRegistryService;
        this.availabilityGate = availabilityGate;
        this.viewportSyncService = viewportSyncService;
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
