package com.linlay.agentplatform.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledQueryOrchestratorTest {

    @Test
    void shouldRegisterAndCancelSchedulesOnReconcile() {
        ScheduledQueryRegistryService registryService = mock(ScheduledQueryRegistryService.class);
        ScheduledQueryDispatchService dispatchService = mock(ScheduledQueryDispatchService.class);
        ScheduleProperties properties = new ScheduleProperties();
        properties.setEnabled(true);
        properties.setDefaultZoneId("Asia/Shanghai");
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class)))
                .thenReturn((ScheduledFuture) future);

        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "daily",
                "Daily Summary",
                "每天早上汇总一次",
                true,
                "0 0 9 * * *",
                null,
                "demoModePlain",
                null,
                "hello",
                Map.of(),
                "/tmp/daily.yml"
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
        ScheduleProperties properties = new ScheduleProperties();
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

    @Test
    void shouldRegisterViewportWeatherScheduleWithConfiguredZone() {
        ScheduledQueryRegistryService registryService = mock(ScheduledQueryRegistryService.class);
        ScheduledQueryDispatchService dispatchService = mock(ScheduledQueryDispatchService.class);
        ScheduleProperties properties = new ScheduleProperties();
        properties.setEnabled(true);
        properties.setDefaultZoneId("UTC");
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn((ScheduledFuture) future);

        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "demo_viewport_weather_minutely",
                "分钟天气视图播报",
                "每分钟触发一次天气视图查询",
                true,
                "0 * * * * *",
                "Asia/Shanghai",
                "demoViewport",
                null,
                "请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。",
                Map.of(),
                "/tmp/demo_viewport_weather_minutely.yml"
        );
        when(registryService.snapshot()).thenReturn(Map.of(descriptor.id(), descriptor));

        ScheduledQueryOrchestrator orchestrator = new ScheduledQueryOrchestrator(
                registryService,
                dispatchService,
                properties,
                taskScheduler
        );

        var triggerCaptor = forClass(Trigger.class);
        orchestrator.reconcile();

        verify(taskScheduler).schedule(any(Runnable.class), triggerCaptor.capture());
        assertThat(triggerCaptor.getValue()).isInstanceOf(CronTrigger.class);
        CronTrigger cronTrigger = (CronTrigger) triggerCaptor.getValue();
        assertThat(cronTrigger.getExpression()).isEqualTo("0 * * * * *");
        assertThat(ReflectionTestUtils.getField(cronTrigger, "zoneId")).hasToString("Asia/Shanghai");
    }
}
