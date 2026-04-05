package com.linlay.agentplatform.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.integration.viewport.ViewportServerRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class McpStreamableHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseJsonRpcBodyDirectly() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(objectMapper, WebClient.builder());
        JsonNode parsed = ReflectionTestUtils.invokeMethod(
                client,
                "parseResponsePayload",
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}"
        );
        assertThat(parsed).isNotNull();
        assertThat(parsed.path("result").path("tools").isArray()).isTrue();
    }

    @Test
    void shouldParseJsonRpcBodyFromSsePayload() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(objectMapper, WebClient.builder());
        String ssePayload = """
                event: message
                data: {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"mock.weather.query"}]}}

                """;

        JsonNode parsed = ReflectionTestUtils.invokeMethod(client, "parseResponsePayload", ssePayload);
        assertThat(parsed).isNotNull();
        assertThat(parsed.path("result").path("tools").get(0).path("name").asText())
                .isEqualTo("mock.weather.query");
    }

    @Test
    void shouldParseJsonRpcBodyFromPlainJsonLines() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(objectMapper, WebClient.builder());
        String linePayload = """
                event: message
                {"jsonrpc":"2.0","id":"0","result":{"tools":[]}}
                {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"mock.weather.query"}]}}
                """;

        JsonNode parsed = ReflectionTestUtils.invokeMethod(client, "parseResponsePayload", linePayload);
        assertThat(parsed).isNotNull();
        assertThat(parsed.path("result").path("tools").get(0).path("name").asText())
                .isEqualTo("mock.weather.query");
    }

    @Test
    void shouldListViewportsFromSsePayload() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(new FixedResponseExchange("""
                        data: {"jsonrpc":"2.0","id":"2","result":{"viewports":[{"viewportKey":"show_weather_card","viewportType":"html"}]}}

                        """))
        );

        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                java.util.Map.of(),
                3000,
                15000,
                0
        );

        assertThat(client.listViewports(server))
                .singleElement()
                .extracting(McpStreamableHttpClient.RemoteViewportSummary::viewportKey,
                        McpStreamableHttpClient.RemoteViewportSummary::viewportType)
                .containsExactly("show_weather_card", "html");
    }

    @Test
    void shouldSkipInvalidViewportSummariesMissingRequiredFields() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(new FixedResponseExchange("""
                        data: {"jsonrpc":"2.0","id":"2","result":{"viewports":[{"viewportKey":"show_weather_card"},{"viewportType":"html"},{"viewportKey":"flight_form","viewportType":"qlc"}]}}

                        """))
        );

        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                java.util.Map.of(),
                3000,
                15000,
                0
        );

        assertThat(client.listViewports(server))
                .singleElement()
                .extracting(McpStreamableHttpClient.RemoteViewportSummary::viewportKey,
                        McpStreamableHttpClient.RemoteViewportSummary::viewportType)
                .containsExactly("flight_form", "qlc");
    }

    @Test
    void shouldGetHtmlViewportPayload() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(new FixedResponseExchange("""
                        data: {"jsonrpc":"2.0","id":"3","result":{"viewportType":"html","payload":"<div>weather</div>"}}

                        """))
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                java.util.Map.of(),
                3000,
                15000,
                0
        );

        McpStreamableHttpClient.RemoteViewportPayload payload = client.getViewport(server, "show_weather_card");
        assertThat(payload.viewportType()).isEqualTo("html");
        assertThat(payload.payload().asText()).isEqualTo("<div>weather</div>");
    }

    @Test
    void shouldGetQlcViewportPayload() {
        McpStreamableHttpClient client = new McpStreamableHttpClient(
                objectMapper,
                WebClient.builder().exchangeFunction(new FixedResponseExchange("""
                        data: {"jsonrpc":"2.0","id":"4","result":{"viewportType":"qlc","payload":{"schema":{"type":"object"}}}}

                        """))
        );
        ViewportServerRegistryService.RegisteredServer server = new ViewportServerRegistryService.RegisteredServer(
                "viewport-mock",
                "http://localhost:11969",
                "/mcp",
                java.util.Map.of(),
                3000,
                15000,
                0
        );

        McpStreamableHttpClient.RemoteViewportPayload payload = client.getViewport(server, "flight_form");
        assertThat(payload.viewportType()).isEqualTo("qlc");
        assertThat(payload.payload().path("schema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void shouldIncludeMetaInToolsCallPayload() throws Exception {
        CapturingExchange exchange = new CapturingExchange("""
                data: {"jsonrpc":"2.0","id":"1","result":{"structuredContent":{"ok":true},"content":[{"type":"text","text":"ok"}],"isError":false}}

                """);
        McpStreamableHttpClient client = new McpStreamableHttpClient(objectMapper, WebClient.builder().exchangeFunction(exchange));
        McpServerRegistryService.RegisteredServer server = new McpServerRegistryService.RegisteredServer(
                "mock",
                "http://localhost:11969",
                "/mcp",
                "mock",
                java.util.Map.of(),
                java.util.Map.of(),
                3000,
                15000,
                0
        );

        client.callTool(
                server,
                "mock.weather.query",
                java.util.Map.of("city", "Shanghai"),
                java.util.Map.of("chatId", "123e4567-e89b-12d3-a456-426614174040")
        );

        JsonNode payload = objectMapper.readTree(exchange.requestBody());
        assertThat(payload.path("params").path("_meta").path("chatId").asText())
                .isEqualTo("123e4567-e89b-12d3-a456-426614174040");
        assertThat(payload.path("params").path("arguments").path("city").asText()).isEqualTo("Shanghai");
    }

    private static final class FixedResponseExchange implements ExchangeFunction {
        private final String body;

        private FixedResponseExchange(String body) {
            this.body = body;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            return Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body(body)
                            .build()
                    );
        }
    }

    private static final class CapturingExchange implements ExchangeFunction {
        private final String body;
        private String requestBody;

        private CapturingExchange(String body) {
            this.body = body;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            MockClientHttpRequest mockRequest = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(mockRequest, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(() -> mockRequest.getBodyAsString()
                            .defaultIfEmpty("")
                            .map(bodyText -> {
                                requestBody = bodyText;
                                return ClientResponse.create(HttpStatus.OK)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                                        .body(body)
                                        .build();
                            })));
        }

        private String requestBody() {
            return requestBody;
        }
    }
}
