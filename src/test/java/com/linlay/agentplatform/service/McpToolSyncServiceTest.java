package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolSyncServiceTest {

    @Test
    void shouldRefreshCapabilitiesWithoutLegacyAliases() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);

        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                1
        );
        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server));

        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", new ObjectMapper().createObjectNode());

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(server, "2025-06");
        when(client.listTools(server)).thenReturn(List.of(
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.weather.query",
                        "weather",
                        "use weather viewport card",
                        schema,
                        false,
                        null,
                        null,
                        List.of()
                )
        ));

        McpToolSyncService service = new McpToolSyncService(
                properties,
                registryService,
                new McpServerAvailabilityGate(properties),
                client,
                new ObjectMapper()
        );
        CatalogDiff diff = service.refreshTools();

        assertThat(service.find("mock.weather.query")).isPresent();
        assertThat(service.find("mock_city_weather")).isEmpty();
        assertThat(diff.addedKeys()).contains("mock.weather.query");
        assertThat(service.find("mock.weather.query").orElseThrow().sourceKey()).isEqualTo("mock");
        assertThat(service.find("mock.weather.query").orElseThrow().afterCallHint())
                .isEqualTo("use weather viewport card");
    }

    @Test
    void shouldBeEmptyWhenMcpDisabled() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpToolSyncService service = new McpToolSyncService(
                properties,
                mock(McpServerRegistryService.class),
                new McpServerAvailabilityGate(properties),
                mock(McpStreamableHttpClient.class),
                new ObjectMapper()
        );

        CatalogDiff diff = service.refreshTools();
        assertThat(service.list()).isEmpty();
        assertThat(service.find("any.tool")).isEmpty();
        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    void shouldRetryFailedServerOnlyAfterReconnectInterval() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);

        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                1
        );
        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server));

        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", new ObjectMapper().createObjectNode());

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing()
                .doThrow(new IllegalStateException("connect failed"))
                .doNothing()
                .when(client)
                .initialize(server, "2025-06");
        when(client.listTools(server)).thenReturn(List.of(
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.weather.query",
                        "weather",
                        "use weather viewport card",
                        schema,
                        false,
                        null,
                        null,
                        List.of()
                )
        ));

        MutableClock clock = new MutableClock(Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("UTC"));
        McpServerAvailabilityGate gate = new McpServerAvailabilityGate(clock, properties.getReconnectIntervalMs());
        McpToolSyncService service = new McpToolSyncService(
                properties,
                registryService,
                gate,
                client,
                new ObjectMapper()
        );

        service.refreshTools();
        assertThat(service.find("mock.weather.query")).isPresent();

        service.refreshToolsForServers(Set.of("mock"));
        assertThat(service.find("mock.weather.query")).isPresent();
        assertThat(gate.isBlocked("mock")).isTrue();

        CatalogDiff skipped = service.refreshToolsForServers(gate.readyToRetry(Set.of("mock")));
        assertThat(skipped.isEmpty()).isTrue();
        assertThat(service.find("mock.weather.query")).isPresent();

        clock.advanceSeconds(60);
        CatalogDiff diff = service.refreshToolsForServers(gate.readyToRetry(Set.of("mock")));
        assertThat(diff.isEmpty()).isTrue();
        assertThat(service.find("mock.weather.query")).isPresent();
        assertThat(gate.isBlocked("mock")).isFalse();

        verify(client, times(3)).initialize(server, "2025-06");
        verify(client, times(2)).listTools(server);
    }

    @Test
    void shouldClassifyFrontendAndActionToolsFromMcpMetadata() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);

        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                1
        );
        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server));

        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", new ObjectMapper().createObjectNode());

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(server, "2025-06");
        when(client.listTools(server)).thenReturn(List.of(
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.todo.tasks.list",
                        "todo",
                        "",
                        schema,
                        false,
                        "html",
                        "show_todo_card",
                        List.of()
                ),
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.sensitive-data.detect",
                        "sensitive",
                        "",
                        schema,
                        true,
                        null,
                        null,
                        List.of()
                )
        ));

        McpToolSyncService service = new McpToolSyncService(
                properties,
                registryService,
                new McpServerAvailabilityGate(properties),
                client,
                new ObjectMapper()
        );
        service.refreshTools();

        assertThat(service.find("mock.todo.tasks.list")).isPresent();
        assertThat(service.find("mock.todo.tasks.list").orElseThrow().kind()).isEqualTo(com.linlay.agentplatform.tool.ToolKind.FRONTEND);
        assertThat(service.find("mock.todo.tasks.list").orElseThrow().toolType()).isEqualTo("html");
        assertThat(service.find("mock.todo.tasks.list").orElseThrow().viewportKey()).isEqualTo("show_todo_card");
        assertThat(service.find("mock.todo.tasks.list").orElseThrow().requiresFrontendSubmit()).isFalse();
        assertThat(service.findViewport("show_todo_card")).isPresent();
        assertThat(service.findViewport("show_todo_card").orElseThrow().serverKey()).isEqualTo("mock");
        assertThat(service.find("mock.sensitive-data.detect").orElseThrow().kind()).isEqualTo(com.linlay.agentplatform.tool.ToolKind.ACTION);
        assertThat(service.find("mock.sensitive-data.detect").orElseThrow().toolAction()).isTrue();
    }

    @Test
    void shouldSkipConflictedRemoteViewportKeys() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);

        McpServerRegistryService.RegisteredServer first = new McpServerRegistryService.RegisteredServer(
                "mock-a",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                1
        );
        McpServerRegistryService.RegisteredServer second = new McpServerRegistryService.RegisteredServer(
                "mock-b",
                "http://localhost:18081",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                1
        );
        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(first, second));

        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", new ObjectMapper().createObjectNode());

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(first, "2025-06");
        doNothing().when(client).initialize(second, "2025-06");
        when(client.listTools(first)).thenReturn(List.of(
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.todo.tasks.list",
                        "todo",
                        "",
                        schema,
                        false,
                        "html",
                        "show_todo_card",
                        List.of()
                )
        ));
        when(client.listTools(second)).thenReturn(List.of(
                new McpStreamableHttpClient.McpToolDefinition(
                        "mock.weather.query",
                        "weather",
                        "",
                        schema,
                        false,
                        "html",
                        "show_todo_card",
                        List.of()
                )
        ));

        McpToolSyncService service = new McpToolSyncService(
                properties,
                registryService,
                new McpServerAvailabilityGate(properties),
                client,
                new ObjectMapper()
        );
        service.refreshTools();

        assertThat(service.find("mock.todo.tasks.list")).isPresent();
        assertThat(service.find("mock.weather.query")).isPresent();
        assertThat(service.findViewport("show_todo_card")).isEmpty();
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
