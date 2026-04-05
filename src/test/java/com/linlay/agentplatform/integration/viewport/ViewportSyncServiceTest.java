package com.linlay.agentplatform.integration.viewport;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.util.CatalogDiff;
import com.linlay.agentplatform.integration.mcp.McpStreamableHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViewportSyncServiceTest {

    @Test
    void shouldRegisterViewportSummariesFromViewportServers() {
        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setEnabled(true);

        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server));

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(server, "2025-06");
        when(client.listViewports(server)).thenReturn(List.of(
                new McpStreamableHttpClient.RemoteViewportSummary(
                        "show_weather_card",
                        "html"
                )
        ));

        ViewportSyncService service = new ViewportSyncService(
                properties,
                registryService,
                new ViewportServerAvailabilityGate(properties),
                client
        );
        CatalogDiff diff = service.refreshViewports();

        assertThat(diff.addedKeys()).contains("show_weather_card");
        assertThat(service.findViewport("show_weather_card")).isPresent();
        assertThat(service.findViewport("show_weather_card").orElseThrow().viewportType()).isEqualTo("html");
        assertThat(service.findViewport("show_weather_card").orElseThrow().serverKey()).isEqualTo("viewport-mock");
    }

    @Test
    void shouldSkipDuplicateViewportKeysAcrossServers() {
        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setEnabled(true);

        ViewportServerRegistryService.RegisteredServer first = new ViewportServerRegistryService.RegisteredServer(
                "viewport-a",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
        ViewportServerRegistryService.RegisteredServer second = new ViewportServerRegistryService.RegisteredServer(
                "viewport-b",
                "http://localhost:11962",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(first, second));

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(first, "2025-06");
        doNothing().when(client).initialize(second, "2025-06");
        when(client.listViewports(first)).thenReturn(List.of(
                new McpStreamableHttpClient.RemoteViewportSummary("shared", "html")
        ));
        when(client.listViewports(second)).thenReturn(List.of(
                new McpStreamableHttpClient.RemoteViewportSummary("shared", "qlc")
        ));

        ViewportSyncService service = new ViewportSyncService(
                properties,
                registryService,
                new ViewportServerAvailabilityGate(properties),
                client
        );
        service.refreshViewports();

        assertThat(service.findViewport("shared")).isEmpty();
    }

    @Test
    void shouldTreatUnsupportedViewportsAsEmptyRegistryForThatServer() {
        ViewportServerProperties properties = new ViewportServerProperties();
        properties.setEnabled(true);

        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        when(registryService.list()).thenReturn(List.of(server));

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        doNothing().when(client).initialize(server, "2025-06");
        doThrow(new McpStreamableHttpClient.RpcErrorException(
                "MCP viewports/list failed",
                new McpStreamableHttpClient.RpcError(-32601, "Method not found", null)
        )).when(client).listViewports(server);

        ViewportSyncService service = new ViewportSyncService(
                properties,
                registryService,
                new ViewportServerAvailabilityGate(properties),
                client
        );
        CatalogDiff diff = service.refreshViewports();

        assertThat(diff.isEmpty()).isTrue();
        assertThat(service.list()).isEmpty();
    }
}
