package com.linlay.agentplatform.service.mcp;

import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.service.CatalogDiff;
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

class McpReconnectOrchestratorTest {

    @Test
    void shouldScheduleReconnectLoopWithConfiguredInterval() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        McpProperties properties = new McpProperties();
        properties.setReconnectIntervalMs(45_000);

        McpReconnectOrchestrator orchestrator = new McpReconnectOrchestrator(
                mock(AgentRegistry.class),
                properties,
                mock(McpServerRegistryService.class),
                new McpServerAvailabilityGate(properties),
                mock(McpToolSyncService.class),
                taskScheduler
        );

        orchestrator.initialize();

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMillis(45_000));
    }

    @Test
    void shouldSkipRetryWhenNoServerIsDue() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server()));

        McpToolSyncService toolSyncService = mock(McpToolSyncService.class);
        McpReconnectOrchestrator orchestrator = new McpReconnectOrchestrator(
                mock(AgentRegistry.class),
                properties,
                registryService,
                new McpServerAvailabilityGate(properties),
                toolSyncService,
                mock(TaskScheduler.class)
        );

        orchestrator.retryDueServers();

        verify(toolSyncService, never()).refreshToolsForServers(any());
    }

    @Test
    void shouldRefreshAffectedAgentsWhenDueServerRecovers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("UTC"));

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);

        McpServerAvailabilityGate gate = new McpServerAvailabilityGate(clock, properties.getReconnectIntervalMs());
        gate.markFailure("mock");
        clock.advanceSeconds(60);

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server()));

        McpToolSyncService toolSyncService = mock(McpToolSyncService.class);
        CatalogDiff diff = new CatalogDiff(Set.of("mock.weather.query"), Set.of(), Set.of());
        when(toolSyncService.refreshToolsForServers(eq(Set.of("mock")))).thenReturn(diff);

        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        when(agentRegistry.findAgentIdsByTools(diff.changedKeys())).thenReturn(Set.of("agent.alpha"));

        McpReconnectOrchestrator orchestrator = new McpReconnectOrchestrator(
                agentRegistry,
                properties,
                registryService,
                gate,
                toolSyncService,
                mock(TaskScheduler.class)
        );

        orchestrator.retryDueServers();

        verify(toolSyncService).refreshToolsForServers(Set.of("mock"));
        verify(agentRegistry).findAgentIdsByTools(diff.changedKeys());
        verify(agentRegistry).refreshAgentsByIds(Set.of("agent.alpha"), "mcp-reconnect");
    }

    private static McpServerRegistryService.RegisteredServer server() {
        return new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15_000,
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
