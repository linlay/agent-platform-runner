package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.model.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpViewportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnHtmlViewportFromViewportServer() {
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);

        ViewportSyncService.RemoteViewportBinding binding = new ViewportSyncService.RemoteViewportBinding(
                "show_weather_card",
                "html",
                List.of("mock.weather.query"),
                "viewport-mock"
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );

        when(viewportSyncService.findViewport("show_weather_card")).thenReturn(Optional.of(binding));
        when(registryService.find("viewport-mock")).thenReturn(Optional.of(server));
        when(client.getViewport(server, "show_weather_card")).thenReturn(
                new McpStreamableHttpClient.RemoteViewportPayload("html", objectMapper.getNodeFactory().textNode("<div>remote</div>"))
        );

        McpViewportService service = new McpViewportService(viewportSyncService, registryService, client);
        ResponseEntity<ApiResponse<Object>> response = service.fetchViewport("show_weather_card").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(Map.of("html", "<div>remote</div>"));
    }

    @Test
    void shouldReturnQlcViewportObjectFromViewportServer() {
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);

        ViewportSyncService.RemoteViewportBinding binding = new ViewportSyncService.RemoteViewportBinding(
                "flight_form",
                "qlc",
                List.of(),
                "viewport-mock"
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("schema", objectMapper.createObjectNode().put("type", "object"));

        when(viewportSyncService.findViewport("flight_form")).thenReturn(Optional.of(binding));
        when(registryService.find("viewport-mock")).thenReturn(Optional.of(server));
        when(client.getViewport(server, "flight_form")).thenReturn(
                new McpStreamableHttpClient.RemoteViewportPayload("qlc", payload)
        );
        when(client.parseJson(payload.toString())).thenReturn(payload);

        McpViewportService service = new McpViewportService(viewportSyncService, registryService, client);
        ResponseEntity<ApiResponse<Object>> response = service.fetchViewport("flight_form").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isInstanceOfSatisfying(com.fasterxml.jackson.databind.JsonNode.class,
                node -> assertThat(node.path("schema").path("type").asText()).isEqualTo("object"));
    }

    @Test
    void shouldMapInvalidParamsTo404() {
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);

        ViewportSyncService.RemoteViewportBinding binding = new ViewportSyncService.RemoteViewportBinding(
                "missing",
                "html",
                List.of(),
                "viewport-mock"
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );

        when(viewportSyncService.findViewport("missing")).thenReturn(Optional.of(binding));
        when(registryService.find("viewport-mock")).thenReturn(Optional.of(server));
        when(client.getViewport(server, "missing")).thenThrow(
                new McpStreamableHttpClient.RpcErrorException(
                        "MCP viewports/get failed",
                        new McpStreamableHttpClient.RpcError(-32602, "invalid params", null)
                )
        );

        McpViewportService service = new McpViewportService(viewportSyncService, registryService, client);
        ResponseEntity<ApiResponse<Object>> response = service.fetchViewport("missing").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(404);
    }

    @Test
    void shouldMapServerErrorsTo502() {
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);

        ViewportSyncService.RemoteViewportBinding binding = new ViewportSyncService.RemoteViewportBinding(
                "show_weather_card",
                "html",
                List.of(),
                "viewport-mock"
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );

        when(viewportSyncService.findViewport("show_weather_card")).thenReturn(Optional.of(binding));
        when(registryService.find("viewport-mock")).thenReturn(Optional.of(server));
        when(client.getViewport(server, "show_weather_card")).thenThrow(new IllegalStateException("connection refused"));

        McpViewportService service = new McpViewportService(viewportSyncService, registryService, client);
        ResponseEntity<ApiResponse<Object>> response = service.fetchViewport("show_weather_card").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(502);
    }

    @Test
    void shouldRetryViewportFetchAfterRefreshingRemoteBindings() {
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);
        ViewportServerRegistryService registryService = mock(ViewportServerRegistryService.class);
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);

        ViewportSyncService.RemoteViewportBinding binding = new ViewportSyncService.RemoteViewportBinding(
                "show_weather_card",
                "html",
                List.of(),
                "viewport-mock"
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                Map.of(),
                3000,
                15000,
                1
        );

        when(viewportSyncService.findViewport("show_weather_card"))
                .thenReturn(Optional.of(binding))
                .thenReturn(Optional.of(binding));
        when(registryService.find("viewport-mock")).thenReturn(Optional.of(server));
        when(client.getViewport(server, "show_weather_card"))
                .thenThrow(new IllegalStateException("connection reset"))
                .thenReturn(new McpStreamableHttpClient.RemoteViewportPayload(
                        "html",
                        objectMapper.getNodeFactory().textNode("<div>remote</div>")
                ));

        McpViewportService service = new McpViewportService(viewportSyncService, registryService, client);
        ResponseEntity<ApiResponse<Object>> response = service.fetchViewport("show_weather_card").orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(Map.of("html", "<div>remote</div>"));
        verify(viewportSyncService).refreshViewportsForServers(java.util.Set.of("viewport-mock"));
    }
}
