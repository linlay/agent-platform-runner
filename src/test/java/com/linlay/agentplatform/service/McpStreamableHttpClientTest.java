package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

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
}
