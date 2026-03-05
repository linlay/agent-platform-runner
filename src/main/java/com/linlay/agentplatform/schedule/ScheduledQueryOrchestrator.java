package com.linlay.agentplatform.schedule;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduledQueryOrchestrator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryOrchestrator.class);

    private final ScheduledQueryRegistryService registryService;
    private final ScheduledQueryDispatchService dispatchService;
    private final ScheduleCatalogProperties properties;
    private final TaskScheduler taskScheduler;

    private final Object lock = new Object();
    private final Map<String, Registration> registrations = new LinkedHashMap<>();

    public ScheduledQueryOrchestrator(
            ScheduledQueryRegistryService registryService,
            ScheduledQueryDispatchService dispatchService,
            ScheduleCatalogProperties properties,
            @Qualifier("scheduledQueryTaskScheduler") TaskScheduler taskScheduler
    ) {
        this.registryService = registryService;
        this.dispatchService = dispatchService;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void initialize() {
        reconcile();
    }

    public void refreshAndReconcile() {
        registryService.refreshSchedules();
        reconcile();
    }

    public void reconcile() {
        synchronized (lock) {
            Map<String, ScheduledQueryDescriptor> desired = registryService.snapshot();
            if (!properties.isEnabled()) {
                cancelAll();
                return;
            }

            for (ScheduledQueryDescriptor descriptor : desired.values()) {
                if (descriptor == null || !descriptor.enabled()) {
                    continue;
                }
                Registration existing = registrations.get(descriptor.id());
                if (existing != null && existing.descriptor().equals(descriptor)) {
                    continue;
                }
                if (existing != null) {
                    cancelRegistration(existing);
                    registrations.remove(descriptor.id());
                }
                schedule(descriptor);
            }

            List<String> staleIds = new ArrayList<>();
            for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
                ScheduledQueryDescriptor descriptor = desired.get(entry.getKey());
                if (descriptor != null && descriptor.enabled()) {
                    continue;
                }
                staleIds.add(entry.getKey());
            }
            for (String staleId : staleIds) {
                Registration registration = registrations.remove(staleId);
                if (registration != null) {
                    cancelRegistration(registration);
                }
            }
        }
    }

    private void schedule(ScheduledQueryDescriptor descriptor) {
        ZoneId zoneId = resolveZoneId(descriptor.zoneId(), properties.getDefaultZoneId());
        CronTrigger trigger = new CronTrigger(descriptor.cron(), zoneId);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> dispatchService.dispatch(descriptor),
                trigger
        );
        if (future == null) {
            log.warn("Cannot register schedule '{}': scheduler returned null future", descriptor.id());
            return;
        }
        registrations.put(descriptor.id(), new Registration(descriptor, future));
        log.info(
                "Registered schedule id={}, cron={}, zoneId={}, source={}",
                descriptor.id(),
                descriptor.cron(),
                zoneId,
                descriptor.sourceFile()
        );
    }

    private ZoneId resolveZoneId(String preferredZoneId, String defaultZoneId) {
        if (StringUtils.hasText(preferredZoneId)) {
            try {
                return ZoneId.of(preferredZoneId.trim());
            } catch (Exception ex) {
                log.warn("Invalid schedule zoneId '{}', fallback to default/system", preferredZoneId);
            }
        }
        if (StringUtils.hasText(defaultZoneId)) {
            try {
                return ZoneId.of(defaultZoneId.trim());
            } catch (Exception ex) {
                log.warn("Invalid default schedule zoneId '{}', fallback to system", defaultZoneId);
            }
        }
        return ZoneId.systemDefault();
    }

    private void cancelAll() {
        for (Registration registration : registrations.values()) {
            cancelRegistration(registration);
        }
        registrations.clear();
    }

    private void cancelRegistration(Registration registration) {
        try {
            registration.future().cancel(false);
            log.info("Unregistered schedule id={}", registration.descriptor().id());
        } catch (Exception ex) {
            log.warn("Error cancelling schedule id={}", registration.descriptor().id(), ex);
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            cancelAll();
        }
    }

    private record Registration(
            ScheduledQueryDescriptor descriptor,
            ScheduledFuture<?> future
    ) {
    }
}
