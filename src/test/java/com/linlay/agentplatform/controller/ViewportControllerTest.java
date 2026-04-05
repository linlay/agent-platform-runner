package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.ViewportType;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.integration.mcp.McpViewportService;
import com.linlay.agentplatform.integration.viewport.ViewportRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViewportControllerTest {

    @Test
    void shouldUseLocalViewportBeforeRemoteFallback() {
        ViewportRegistryService localRegistry = mock(ViewportRegistryService.class);
        McpViewportService remoteService = mock(McpViewportService.class);
        when(localRegistry.find("show_weather_card")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("show_weather_card", ViewportType.HTML, "<div>local</div>")
        ));

        ViewportController controller = new ViewportController(localRegistry, remoteService, new LoggingAgentProperties());
        ResponseEntity<ApiResponse<Object>> response = controller.viewport("show_weather_card").block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(Map.of("html", "<div>local</div>"));
    }

    @Test
    void shouldFallbackToRemoteViewportWhenLocalMisses() {
        ViewportRegistryService localRegistry = mock(ViewportRegistryService.class);
        McpViewportService remoteService = mock(McpViewportService.class);
        when(localRegistry.find("show_weather_card")).thenReturn(Optional.empty());
        when(remoteService.fetchViewport("show_weather_card")).thenReturn(Optional.of(
                ResponseEntity.ok(ApiResponse.success(Map.of("html", "<div>remote</div>")))
        ));

        ViewportController controller = new ViewportController(localRegistry, remoteService, new LoggingAgentProperties());
        ResponseEntity<ApiResponse<Object>> response = controller.viewport("show_weather_card").block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(Map.of("html", "<div>remote</div>"));
    }

    @Test
    void shouldPassThroughRemoteViewportStatus() {
        ViewportRegistryService localRegistry = mock(ViewportRegistryService.class);
        McpViewportService remoteService = mock(McpViewportService.class);
        when(localRegistry.find("missing")).thenReturn(Optional.empty());
        when(remoteService.fetchViewport("missing")).thenReturn(Optional.of(
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure(404, "Viewport not found: missing", (Object) Map.of()))
        ));

        ViewportController controller = new ViewportController(localRegistry, remoteService, new LoggingAgentProperties());
        ResponseEntity<ApiResponse<Object>> response = controller.viewport("missing").block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(404);
    }
}
