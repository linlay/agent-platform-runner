package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.SystemContainerHubBash;
import com.linlay.agentplatform.tool.ToolDescriptor;
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
        when(toolRegistry.descriptor("datetime")).thenReturn(Optional.of(new ToolDescriptor(
                "datetime",
                null,
                "local",
                "",
                Map.of("type", "object"),
                false,
                true,
                false,
                null,
                "local",
                null,
                null,
                "java://builtin"
        )));
        LocalToolInvoker localToolInvoker = mock(LocalToolInvoker.class);
        when(localToolInvoker.invoke("datetime", Map.of("timezone", "Asia/Shanghai"), null))
                .thenReturn(TextNode.valueOf("LOCAL_OK"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ToolInvokerRouter router = new ToolInvokerRouter(
                toolRegistry,
                localToolInvoker,
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );

        assertThat(router.invoke("datetime", Map.of("timezone", "Asia/Shanghai"), null).asText()).isEqualTo("LOCAL_OK");
        verify(localToolInvoker).invoke("datetime", Map.of("timezone", "Asia/Shanghai"), null);
    }

    @Test
    void shouldRouteMcpToolsToMcpInvoker() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("mock.weather.query")).thenReturn(Optional.of(new ToolDescriptor(
                "mock.weather.query",
                null,
                "mcp",
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

    @Test
    void sandboxBashShouldRouteToLocalInvokerBecauseItIsNotMcp() {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:11960");
        SystemContainerHubBash containerHubTool = new SystemContainerHubBash(
                properties,
                new ContainerHubClient(properties, objectMapper)
        );
        ToolRegistry toolRegistry = new ToolRegistry(java.util.List.of(containerHubTool));
        LocalToolInvoker localToolInvoker = mock(LocalToolInvoker.class);
        when(localToolInvoker.invoke("sandbox_bash", Map.of("command", "pwd"), null))
                .thenReturn(TextNode.valueOf("LOCAL_CONTAINER_HUB"));
        McpToolInvoker mcpToolInvoker = mock(McpToolInvoker.class);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("mcpToolInvoker", mcpToolInvoker);
        ToolInvokerRouter router = new ToolInvokerRouter(
                toolRegistry,
                localToolInvoker,
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );

        assertThat(router.invoke("sandbox_bash", Map.of("command", "pwd"), null).asText())
                .isEqualTo("LOCAL_CONTAINER_HUB");
        verify(localToolInvoker).invoke("sandbox_bash", Map.of("command", "pwd"), null);
    }
}
