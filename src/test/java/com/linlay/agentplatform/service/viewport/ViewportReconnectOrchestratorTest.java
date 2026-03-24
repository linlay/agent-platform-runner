package com.linlay.agentplatform.service.viewport;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewportReconnectOrchestratorTest {

    @Test
    void shouldScheduleReconnectLoopWithConfiguredInterval() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setReconnectIntervalMs(45_000);

        ViewportReconnectOrchestrator orchestrator = new ViewportReconnectOrchestrator(
                properties,
                mock(ViewportServerRegistryService.class),
                new ViewportServerAvailabilityGate(properties),
                mock(ViewportSyncService.class),
                taskScheduler
        );

        orchestrator.initialize();

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMillis(45_000));
    }

    @Test
    void shouldSkipRetryWhenNoServerIsDue() {
        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);

        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server()));

        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportReconnectOrchestrator orchestrator = new ViewportReconnectOrchestrator(
                properties,
                registryService,
                new ViewportServerAvailabilityGate(properties),
                viewportSyncService,
                mock(TaskScheduler.class)
        );

        orchestrator.retryDueServers();

        verify(viewportSyncService, never()).refreshViewportsForServers(any());
    }

    @Test
    void shouldRefreshDueViewportServersWhenCooldownExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("UTC"));
        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);

        ViewportServerAvailabilityGate gate = new ViewportServerAvailabilityGate(clock, properties.getReconnectIntervalMs());
        gate.markFailure("viewport-mock");
        clock.advanceSeconds(60);

        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server()));

        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);

        ViewportReconnectOrchestrator orchestrator = new ViewportReconnectOrchestrator(
                properties,
                registryService,
                gate,
                viewportSyncService,
                mock(TaskScheduler.class)
        );

        orchestrator.retryDueServers();

        verify(viewportSyncService).refreshViewportsForServers(eq(Set.of("viewport-mock")));
    }

    private static ViewportServerRegistryService.RegisteredServer server() {
        return new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zoneId;

        private MutableClock(Instant instant, ZoneId zoneId) {
            this.instant = instant;
            this.zoneId = zoneId;
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
