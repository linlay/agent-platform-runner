package com.linlay.agentplatform.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledQueryOrchestratorTest {

    @Test
    void shouldRegisterAndCancelSchedulesOnReconcile() {
        ScheduledQueryRegistryService registryService = mock(ScheduledQueryRegistryService.class);
        ScheduledQueryDispatchService dispatchService = mock(ScheduledQueryDispatchService.class);
        ScheduleCatalogProperties properties = new ScheduleCatalogProperties();
        properties.setEnabled(true);
        properties.setDefaultZoneId("Asia/Shanghai");
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class)))
                .thenReturn((ScheduledFuture) future);

        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "daily",
                "Daily Summary",
                true,
                "0 0 9 * * *",
                null,
                "demoModePlain",
                null,
                "hello",
                Map.of(),
                "/tmp/daily.json"
        );
        when(registryService.snapshot()).thenReturn(Map.of("daily", descriptor)).thenReturn(Map.of("daily", descriptor)).thenReturn(Map.of());

        ScheduledQueryOrchestrator orchestrator = new ScheduledQueryOrchestrator(
                registryService,
                dispatchService,
                properties,
                taskScheduler
        );

        orchestrator.reconcile();
        orchestrator.reconcile();
        orchestrator.reconcile();

        verify(taskScheduler).schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
        verify(future).cancel(false);
    }

    @Test
    void refreshAndReconcileShouldRefreshRegistry() {
        ScheduledQueryRegistryService registryService = mock(ScheduledQueryRegistryService.class);
        ScheduledQueryDispatchService dispatchService = mock(ScheduledQueryDispatchService.class);
        ScheduleCatalogProperties properties = new ScheduleCatalogProperties();
        properties.setEnabled(false);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);

        ScheduledQueryOrchestrator orchestrator = new ScheduledQueryOrchestrator(
                registryService,
                dispatchService,
                properties,
                taskScheduler
        );
        orchestrator.refreshAndReconcile();

        verify(registryService).refreshSchedules();
    }
}
