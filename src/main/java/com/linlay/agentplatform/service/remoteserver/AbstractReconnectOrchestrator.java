package com.linlay.agentplatform.service.remoteserver;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

public abstract class AbstractReconnectOrchestrator implements DisposableBean {

    private final TaskScheduler taskScheduler;
    private final Object lifecycleLock = new Object();
    private volatile ScheduledFuture<?> future;

    protected AbstractReconnectOrchestrator(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public final void initialize() {
        synchronized (lifecycleLock) {
            if (future != null) {
                return;
            }
            long intervalMs = Math.max(1L, getReconnectIntervalMs());
            future = taskScheduler.scheduleWithFixedDelay(this::runScheduledRetry, Duration.ofMillis(intervalMs));
        }
    }

    private void runScheduledRetry() {
        if (!isEnabled()) {
            return;
        }
        retryDueServers();
    }

    @Override
    public final void destroy() {
        synchronized (lifecycleLock) {
            if (future == null) {
                return;
            }
            future.cancel(false);
            future = null;
        }
    }

    protected abstract boolean isEnabled();

    protected abstract long getReconnectIntervalMs();

    protected abstract void retryDueServers();
}
