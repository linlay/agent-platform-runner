package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.service.McpServerRegistryService;
import com.linlay.agentplatform.service.McpStreamableHttpClient;
import com.linlay.agentplatform.tool.CapabilityDescriptor;
import com.linlay.agentplatform.tool.CapabilityKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                mock(McpStreamableHttpClient.class),
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of(), null).path("code").asText())
                .isEqualTo("mcp_disabled");
    }

    @Test
    void shouldReturnStructuredContentOnSuccessfulCall() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                "mock.weather.query",
                "weather",
                "",
                Map.of("type", "object"),
                false,
                CapabilityKind.BACKEND,
                "function",
                "mcp://mock/mock.weather.query",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        when(toolRegistry.capability("mock.weather.query")).thenReturn(Optional.of(descriptor));

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
                client,
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null)
                .path("temperatureC").asInt()).isEqualTo(21);
    }
}
