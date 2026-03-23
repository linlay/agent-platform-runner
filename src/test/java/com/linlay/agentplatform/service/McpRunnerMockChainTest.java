package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.McpToolInvoker;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ViewportServerProperties;
import com.linlay.agentplatform.service.mcp.McpServerAvailabilityGate;
import com.linlay.agentplatform.service.mcp.McpServerRegistryService;
import com.linlay.agentplatform.service.mcp.McpStreamableHttpClient;
import com.linlay.agentplatform.service.mcp.McpToolSyncService;
import com.linlay.agentplatform.service.mcp.McpViewportService;
import com.linlay.agentplatform.service.viewport.ViewportServerAvailabilityGate;
import com.linlay.agentplatform.service.viewport.ViewportServerRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportSyncService;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpRunnerMockChainTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldRunInitializeListAndCallChainWithMcpMetadata() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: mock
                baseUrl: http://mock.local
                endpointPath: /mcp
                """);

        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setProtocolVersion("2025-06");
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService serverRegistryService = new McpServerRegistryService(objectMapper, properties);
        serverRegistryService.refreshServers();
        assertThat(serverRegistryService.find("mock")).isPresent();

        AtomicInteger requestCounter = new AtomicInteger();
        ExchangeFunction exchangeFunction = new ScriptedExchangeFunction(requestCounter);
        McpStreamableHttpClient streamableHttpClient = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(exchangeFunction)
        );
        McpServerAvailabilityGate availabilityGate = new McpServerAvailabilityGate(properties);

        McpToolSyncService toolSyncService = new McpToolSyncService(
                properties,
                serverRegistryService,
                availabilityGate,
                streamableHttpClient,
                objectMapper
        );
        toolSyncService.refreshTools();

        assertThat(toolSyncService.find("mock.weather.query"))
                .isPresent()
                .get()
                .extracting(descriptor -> descriptor.sourceType(), descriptor -> descriptor.sourceKey())
                .containsExactly("mcp", "mock");
        assertThat(toolSyncService.find("mock.weather.query"))
                .isPresent()
                .get()
                .extracting(descriptor -> descriptor.label(), descriptor -> descriptor.afterCallHint())
                .containsExactly("天气查询", "Use viewport key=show_weather_card");

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("mcpToolSyncService", toolSyncService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class)
        );

        McpToolInvoker mcpToolInvoker = new McpToolInvoker(
                toolRegistry,
                properties,
                serverRegistryService,
                availabilityGate,
                streamableHttpClient,
                objectMapper
        );

        JsonNode result = mcpToolInvoker.invoke(
                "mock.weather.query",
                Map.of("city", "shanghai", "date", "2026-02-14"),
                null
        );
        assertThat(result.path("city").asText()).isEqualTo("上海");
        assertThat(result.path("date").asText()).isEqualTo("2026-02-14");
        assertThat(result.path("temperatureC").isNumber()).isTrue();
        assertThat(result.path("mockTag").asText()).isEqualTo("幂等随机数据");
        assertThat(requestCounter.get()).isEqualTo(3);
    }

    @Test
    void shouldUseSameEndpointForMcpToolsAndViewportServers() throws Exception {
        Path mcpRegistryDir = tempDir.resolve("mcp-servers");
        Path viewportRegistryDir = tempDir.resolve("viewport-servers");
        Files.createDirectories(mcpRegistryDir);
        Files.createDirectories(viewportRegistryDir);
        String serverYaml = """
                serverKey: mock
                baseUrl: http://mock.local
                endpointPath: /mcp
                """;
        Files.writeString(mcpRegistryDir.resolve("mock.yml"), serverYaml);
        Files.writeString(viewportRegistryDir.resolve("mock.yml"), serverYaml);

        McpProperties mcpProperties = new McpProperties();
        mcpProperties.setEnabled(true);
        mcpProperties.getRegistry().setExternalDir(mcpRegistryDir.toString());

        ViewportServerProperties viewportProperties = new ViewportServerProperties();
        viewportProperties.setEnabled(true);
        viewportProperties.getRegistry().setExternalDir(viewportRegistryDir.toString());

        McpServerRegistryService mcpServerRegistryService = new McpServerRegistryService(objectMapper, mcpProperties);
        mcpServerRegistryService.refreshServers();
        ViewportServerRegistryService viewportServerRegistryService = new ViewportServerRegistryService(objectMapper, viewportProperties);
        viewportServerRegistryService.refreshServers();

        AtomicInteger requestCounter = new AtomicInteger();
        ExchangeFunction exchangeFunction = new DualRegistryExchangeFunction(requestCounter);
        McpStreamableHttpClient streamableHttpClient = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(exchangeFunction)
        );

        McpToolSyncService toolSyncService = new McpToolSyncService(
                mcpProperties,
                mcpServerRegistryService,
                new McpServerAvailabilityGate(mcpProperties),
                streamableHttpClient,
                objectMapper
        );
        toolSyncService.refreshTools();

        ViewportSyncService viewportSyncService = new ViewportSyncService(
                viewportProperties,
                viewportServerRegistryService,
                new ViewportServerAvailabilityGate(viewportProperties),
                streamableHttpClient
        );
        viewportSyncService.refreshViewports();

        McpViewportService viewportService = new McpViewportService(
                viewportSyncService,
                viewportServerRegistryService,
                streamableHttpClient
        );

        assertThat(toolSyncService.find("mock.weather.query")).isPresent();
        assertThat(viewportSyncService.findViewport("show_weather_card")).isPresent();
        assertThat(viewportService.fetchViewport("show_weather_card"))
                .isPresent()
                .get()
                .extracting(response -> response.getBody().data())
                .isEqualTo(Map.of("html", "<div>Weather card</div>"));
        assertThat(requestCounter.get()).isEqualTo(5);
    }

    private static final class ScriptedExchangeFunction implements ExchangeFunction {
        private final AtomicInteger requestCounter;

        private ScriptedExchangeFunction(AtomicInteger requestCounter) {
            this.requestCounter = requestCounter;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            int index = requestCounter.incrementAndGet();
            String responseBody = switch (index) {
                case 1 -> """
                        {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2025-06","capabilities":{"tools":{"listChanged":false}}}}
                        """;
                case 2 -> """
                        data: {"jsonrpc":"2.0","id":"2","result":{"tools":[{"name":"mock.weather.query","label":"天气查询","description":"[MOCK] query weather","afterCallHint":"Use viewport key=show_weather_card","inputSchema":{"type":"object","properties":{"city":{"type":"string"},"date":{"type":"string"}},"additionalProperties":true}}]}}

                        """;
                case 3 -> """
                        data: {"jsonrpc":"2.0","id":"3","result":{"structuredContent":{"city":"上海","date":"2026-02-14","temperatureC":16,"humidity":68,"windLevel":3,"condition":"多云","mockTag":"幂等随机数据"},"content":[{"type":"text","text":"ok"}],"isError":false}}

                        """;
                default -> """
                        {"jsonrpc":"2.0","id":"x","error":{"code":-32603,"message":"unexpected request"}}
                        """;
            };
            String contentType = index == 1 ? MediaType.APPLICATION_JSON_VALUE : MediaType.TEXT_EVENT_STREAM_VALUE;
            return Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(responseBody)
                            .build()
            );
        }
    }

    private static final class DualRegistryExchangeFunction implements ExchangeFunction {
        private final AtomicInteger requestCounter;

        private DualRegistryExchangeFunction(AtomicInteger requestCounter) {
            this.requestCounter = requestCounter;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            int index = requestCounter.incrementAndGet();
            String responseBody = switch (index) {
                case 1, 3 -> """
                        {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2025-06","capabilities":{"tools":{"listChanged":false}}}}
                        """;
                case 2 -> """
                        data: {"jsonrpc":"2.0","id":"2","result":{"tools":[{"name":"mock.weather.query","label":"天气查询","description":"[MOCK] query weather","afterCallHint":"Use viewport key=show_weather_card","inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"additionalProperties":true}}]}}

                        """;
                case 4 -> """
                        data: {"jsonrpc":"2.0","id":"4","result":{"viewports":[{"viewportKey":"show_weather_card","viewportType":"html"}]}}

                        """;
                case 5 -> """
                        {"jsonrpc":"2.0","id":"5","result":{"viewportType":"html","payload":"<div>Weather card</div>"}}
                        """;
                default -> """
                        {"jsonrpc":"2.0","id":"x","error":{"code":-32603,"message":"unexpected request"}}
                        """;
            };
            String contentType = index == 1 || index == 3 || index == 5
                    ? MediaType.APPLICATION_JSON_VALUE
                    : MediaType.TEXT_EVENT_STREAM_VALUE;
            return Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(responseBody)
                            .build()
            );
        }
    }
}
