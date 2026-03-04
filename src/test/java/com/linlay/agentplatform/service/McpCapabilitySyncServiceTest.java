package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpCapabilitySyncServiceTest {

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
        when(registryService.currentVersion()).thenReturn(1L);

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
                        List.of()
                )
        ));

        McpCapabilitySyncService service = new McpCapabilitySyncService(
                properties,
                registryService,
                new McpServerAvailabilityGate(),
                client,
                new ObjectMapper()
        );
        service.refreshCapabilities();

        assertThat(service.find("mock.weather.query")).isPresent();
        assertThat(service.find("mock_city_weather")).isEmpty();
        assertThat(service.find("mock.weather.query").orElseThrow().sourceKey()).isEqualTo("mock");
        assertThat(service.find("mock.weather.query").orElseThrow().afterCallHint())
                .isEqualTo("use weather viewport card");
    }

    @Test
    void shouldBeEmptyWhenMcpDisabled() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpCapabilitySyncService service = new McpCapabilitySyncService(
                properties,
                mock(McpServerRegistryService.class),
                new McpServerAvailabilityGate(),
                mock(McpStreamableHttpClient.class),
                new ObjectMapper()
        );

        service.refreshCapabilities();
        assertThat(service.list()).isEmpty();
        assertThat(service.find("any.tool")).isEmpty();
    }

    @Test
    void shouldFreezeFailedServerUntilRegistryVersionChanges() {
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
        when(registryService.currentVersion()).thenReturn(1L, 1L, 1L, 2L);

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
                        List.of()
                )
        ));

        McpCapabilitySyncService service = new McpCapabilitySyncService(
                properties,
                registryService,
                new McpServerAvailabilityGate(),
                client,
                new ObjectMapper()
        );

        service.refreshCapabilities();
        assertThat(service.find("mock.weather.query")).isPresent();

        service.refreshCapabilities();
        assertThat(service.find("mock.weather.query")).isPresent();

        service.refreshCapabilities();
        assertThat(service.find("mock.weather.query")).isPresent();

        service.refreshCapabilities();
        assertThat(service.find("mock.weather.query")).isPresent();

        verify(client, times(3)).initialize(server, "2025-06");
        verify(client, times(2)).listTools(server);
    }
}
