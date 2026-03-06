package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.service.McpServerAvailabilityGate;
import com.linlay.agentplatform.service.McpServerRegistryService;
import com.linlay.agentplatform.service.McpStreamableHttpClient;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolInvokerTest {

    @Test
    void shouldReturnStructuredErrorWhenMcpDisabled() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpToolInvoker invoker = new McpToolInvoker(
                mock(ToolRegistry.class),
                properties,
                mock(McpServerRegistryService.class),
                new McpServerAvailabilityGate(),
                mock(McpStreamableHttpClient.class),
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of(), null).path("code").asText())
                .isEqualTo("mcp_disabled");
    }

    @Test
    void shouldReturnStructuredContentOnSuccessfulCall() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = new ToolDescriptor(
                "mock.weather.query",
                "weather",
                "",
                Map.of("type", "object"),
                false,
                ToolKind.BACKEND,
                "function",
                "mcp://mock/mock.weather.query",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        when(toolRegistry.toolDescriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                0
        );
        when(registryService.find("mock")).thenReturn(Optional.of(server));
        when(registryService.currentVersion()).thenReturn(1L);

        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("isError", false);
        result.set("structuredContent", new ObjectMapper().createObjectNode().put("temperatureC", 21));
        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        when(client.callTool(server, "mock.weather.query", Map.of("city", "Shanghai")))
                .thenReturn(result);

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolInvoker invoker = new McpToolInvoker(
                toolRegistry,
                properties,
                registryService,
                new McpServerAvailabilityGate(),
                client,
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null)
                .path("temperatureC").asInt()).isEqualTo(21);
    }

    @Test
    void shouldQuickFailWhenServerIsBlockedAtCurrentVersion() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = new ToolDescriptor(
                "mock.weather.query",
                "weather",
                "",
                Map.of("type", "object"),
                false,
                ToolKind.BACKEND,
                "function",
                "mcp://mock/mock.weather.query",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        when(toolRegistry.toolDescriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                0
        );
        when(registryService.find("mock")).thenReturn(Optional.of(server));
        when(registryService.currentVersion()).thenReturn(9L);

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        McpServerAvailabilityGate gate = new McpServerAvailabilityGate();
        gate.markFailure("mock", 9L);

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolInvoker invoker = new McpToolInvoker(
                toolRegistry,
                properties,
                registryService,
                gate,
                client,
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("code").asText())
                .isEqualTo("mcp_server_unavailable");
        verify(client, times(0)).callTool(server, "mock.weather.query", Map.of("city", "Shanghai"));
    }

    @Test
    void shouldMarkServerUnavailableAndSkipSubsequentCallsAtSameVersion() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = new ToolDescriptor(
                "mock.weather.query",
                "weather",
                "",
                Map.of("type", "object"),
                false,
                ToolKind.BACKEND,
                "function",
                "mcp://mock/mock.weather.query",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        when(toolRegistry.toolDescriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:18080",
                "/mcp",
                "mock",
                Map.of(),
                Map.of(),
                3000,
                15000,
                0
        );
        when(registryService.find("mock")).thenReturn(Optional.of(server));
        when(registryService.currentVersion()).thenReturn(3L);

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        when(client.callTool(server, "mock.weather.query", Map.of("city", "Shanghai")))
                .thenThrow(new IllegalStateException("connection refused"));

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        McpToolInvoker invoker = new McpToolInvoker(
                toolRegistry,
                properties,
                registryService,
                new McpServerAvailabilityGate(),
                client,
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("code").asText())
                .isEqualTo("mcp_server_unavailable");
        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("code").asText())
                .isEqualTo("mcp_server_unavailable");
        verify(client, times(1)).callTool(server, "mock.weather.query", Map.of("city", "Shanghai"));
    }
}
