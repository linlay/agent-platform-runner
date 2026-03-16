package com.linlay.agentplatform.schedule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                    "daily",
                    "Daily Summary",
                    "每天早上汇总一次",
                    true,
                    "0 0 9 * * *",
                    "demoModePlain",
                    null,
                    new ScheduledQueryDescriptor.Environment(null),
                    new ScheduledQueryDescriptor.Query(null, null, null, "hello", java.util.List.of(), Map.of(), null, null),
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

            String logs = renderedLogs(appender);
            assertThat(logs).contains("Registered schedule id=daily, name=Daily Summary");
            assertThat(logs).contains("agentKey=demoModePlain");
            assertThat(logs).contains("nextFireTime=");
            assertThat(logs).contains("Schedule registry ready count=1, schedules=Daily Summary(daily)=0 0 9 * * *");
            assertThat(logs).contains("Unregistered schedule id=daily, name=Daily Summary, cron=0 0 9 * * *");
            assertThat(logs).contains("Schedule registry ready count=0, schedules=-");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void refreshAndReconcileShouldRefreshRegistry() {
        ScheduledQueryRegistryService registryService = mock(ScheduledQueryRegistryService.class);
        ScheduledQueryDispatchService dispatchService = mock(ScheduledQueryDispatchService.class);
        ScheduleProperties properties = new ScheduleProperties();
        properties.setEnabled(false);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            ScheduledQueryOrchestrator orchestrator = new ScheduledQueryOrchestrator(
                    registryService,
                    dispatchService,
                    properties,
                    taskScheduler
            );
            orchestrator.refreshAndReconcile();

            verify(registryService).refreshSchedules();
            assertThat(renderedLogs(appender)).contains("Schedule subsystem disabled, skip registration");
        } finally {
            detachAppender(appender);
        }
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
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                    "demo_viewport_weather_minutely",
                    "分钟天气视图播报",
                    "每分钟触发一次天气视图查询",
                    true,
                    "0 * * * * *",
                    "demoViewport",
                    "a1b2c3d4e5f6",
                    new ScheduledQueryDescriptor.Environment("Asia/Shanghai"),
                    new ScheduledQueryDescriptor.Query(
                            null,
                            null,
                            null,
                            "请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。",
                            java.util.List.of(),
                            Map.of(),
                            null,
                            null
                    ),
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
            String logs = renderedLogs(appender);
            assertThat(logs).contains("Registered schedule id=demo_viewport_weather_minutely, name=分钟天气视图播报");
            assertThat(logs).contains("teamId=a1b2c3d4e5f6");
            assertThat(logs).contains("zoneId=Asia/Shanghai");
        } finally {
            detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledQueryOrchestrator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledQueryOrchestrator.class);
        logger.detachAppender(appender);
    }

    private String renderedLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
