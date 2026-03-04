package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linlay.agentplatform.tool.CapabilityDescriptor;
import com.linlay.agentplatform.tool.CapabilityKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolInvokerRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRouteLocalToolsToLocalInvoker() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.capability("city_datetime")).thenReturn(Optional.of(new CapabilityDescriptor(
                "city_datetime",
                "local",
                "",
                Map.of("type", "object"),
                false,
                CapabilityKind.BACKEND,
                "function",
                null,
                "local",
                null,
                null,
                "java://builtin"
        )));
        LocalToolInvoker localToolInvoker = mock(LocalToolInvoker.class);
        when(localToolInvoker.invoke("city_datetime", Map.of("city", "Shanghai"), null))
                .thenReturn(TextNode.valueOf("LOCAL_OK"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ToolInvokerRouter router = new ToolInvokerRouter(
                toolRegistry,
                localToolInvoker,
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );

        assertThat(router.invoke("city_datetime", Map.of("city", "Shanghai"), null).asText()).isEqualTo("LOCAL_OK");
        verify(localToolInvoker).invoke("city_datetime", Map.of("city", "Shanghai"), null);
    }

    @Test
    void shouldRouteMcpToolsToMcpInvoker() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.capability("mock.weather.query")).thenReturn(Optional.of(new CapabilityDescriptor(
                "mock.weather.query",
                "mcp",
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
        )));
        LocalToolInvoker localToolInvoker = mock(LocalToolInvoker.class);
        McpToolInvoker mcpToolInvoker = mock(McpToolInvoker.class);
        when(mcpToolInvoker.invoke("mock.weather.query", Map.of("city", "Shanghai"), null))
                .thenReturn(objectMapper.createObjectNode().put("ok", true));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("mcpToolInvoker", mcpToolInvoker);
        ToolInvokerRouter router = new ToolInvokerRouter(
                toolRegistry,
                localToolInvoker,
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );

        assertThat(router.invoke("mock.weather.query", Map.of("city", "Shanghai"), null).path("ok").asBoolean()).isTrue();
        verify(mcpToolInvoker).invoke("mock.weather.query", Map.of("city", "Shanghai"), null);
    }
}
