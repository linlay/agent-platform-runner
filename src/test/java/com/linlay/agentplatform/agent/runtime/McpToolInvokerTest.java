package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.service.McpServerAvailabilityGate;
import com.linlay.agentplatform.service.McpServerRegistryService;
import com.linlay.agentplatform.service.McpStreamableHttpClient;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
                new McpServerAvailabilityGate(properties),
                mock(McpStreamableHttpClient.class),
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of(), null).path("code").asText())
                .isEqualTo("mcp_disabled");
    }

    @Test
    void shouldReturnStructuredContentOnSuccessfulCall() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = descriptor();
        when(toolRegistry.descriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = server();
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
                new McpServerAvailabilityGate(properties),
                client,
                new ObjectMapper()
        );

        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null)
                .path("temperatureC").asInt()).isEqualTo(21);
    }

    @Test
    void shouldQuickFailWhenServerIsBlockedDuringReconnectCooldown() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = descriptor();
        when(toolRegistry.descriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = server();
        when(registryService.find("mock")).thenReturn(Optional.of(server));

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("UTC"));
        McpServerAvailabilityGate gate = new McpServerAvailabilityGate(clock, 60_000);
        gate.markFailure("mock");

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);
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
        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("error").asText())
                .contains("scheduled reconnect");
        verify(client, times(0)).callTool(server, "mock.weather.query", Map.of("city", "Shanghai"));
    }

    @Test
    void shouldRecoverCallsAfterReconnectCooldownExpires() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDescriptor descriptor = descriptor();
        when(toolRegistry.descriptor("mock.weather.query")).thenReturn(Optional.of(descriptor));

        McpServerRegistryService registryService = mock(McpServerRegistryService.class);
        McpServerRegistryService.RegisteredServer server = server();
        when(registryService.find("mock")).thenReturn(Optional.of(server));

        ObjectNode success = new ObjectMapper().createObjectNode();
        success.put("isError", false);
        success.set("structuredContent", new ObjectMapper().createObjectNode().put("temperatureC", 18));

        McpStreamableHttpClient client = mock(McpStreamableHttpClient.class);
        when(client.callTool(server, "mock.weather.query", Map.of("city", "Shanghai")))
                .thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(success);

        MutableClock clock = new MutableClock(Instant.parse("2026-03-07T00:00:00Z"), ZoneId.of("UTC"));
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setReconnectIntervalMs(60_000);
        McpServerAvailabilityGate gate = new McpServerAvailabilityGate(clock, properties.getReconnectIntervalMs());
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
        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("code").asText())
                .isEqualTo("mcp_server_unavailable");

        clock.advanceSeconds(60);
        assertThat(invoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null)
                .path("temperatureC").asInt()).isEqualTo(18);

        verify(client, times(2)).callTool(server, "mock.weather.query", Map.of("city", "Shanghai"));
    }

    private static ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "mock.weather.query",
                null,
                "weather",
                "",
                Map.of("type", "object"),
                false,
                true,
                false,
                null,
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
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
                15000,
                0
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
