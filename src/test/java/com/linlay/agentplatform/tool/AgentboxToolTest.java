package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.AgentboxToolProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentboxToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeShouldApplyDefaultsAndReturnSuccessBody() throws Exception {
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertThat(request.path("runtime").asText()).isEqualTo("busybox");
            assertThat(request.path("version").asText()).isEqualTo("latest");
            assertThat(request.path("cwd").asText()).isEqualTo("/workspace");
            assertThat(request.path("command").asText()).isEqualTo("/bin/sh");
            assertThat(request.path("args").isArray()).isTrue();
            writeJson(exchange, 200, """
                    {"session_id":"session-123","created":true,"exit_code":0,"stdout":"ok\\n","stderr":"","timed_out":false}
                    """);
        })) {
            AgentboxToolProperties properties = properties(server.baseUrl());
            AgentboxClient client = new AgentboxClient(properties, objectMapper, HttpClient.newHttpClient());
            SystemAgentboxExecute tool = new SystemAgentboxExecute(properties, client);

            JsonNode result = tool.invoke(Map.of(
                    "command", "/bin/sh",
                    "args", java.util.List.of("-lc", "echo ok")
            ));

            assertThat(result.path("session_id").asText()).isEqualTo("session-123");
            assertThat(result.path("created").asBoolean()).isTrue();
            assertThat(result.path("exit_code").asInt()).isEqualTo(0);
        }
    }

    @Test
    void executeShouldSurfaceReadableConflictError() throws Exception {
        try (TestHttpServer server = new TestHttpServer(exchange -> writeJson(exchange, 409, """
                {"error":"session configuration conflict"}
                """))) {
            AgentboxToolProperties properties = properties(server.baseUrl());
            AgentboxClient client = new AgentboxClient(properties, objectMapper, HttpClient.newHttpClient());
            SystemAgentboxExecute tool = new SystemAgentboxExecute(properties, client);

            JsonNode result = tool.invoke(Map.of(
                    "session_id", "demo",
                    "command", "/bin/sh",
                    "args", java.util.List.of("-lc", "pwd")
            ));

            assertThat(result.path("ok").asBoolean()).isFalse();
            assertThat(result.path("status").asInt()).isEqualTo(409);
            assertThat(result.path("error").asText()).contains("conflict");
        }
    }

    @Test
    void stopSessionShouldReturnSuccessBody() throws Exception {
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertThat(request.path("session_id").asText()).isEqualTo("demo-session");
            writeJson(exchange, 200, """
                    {"session_id":"demo-session","status":"stopped"}
                    """);
        })) {
            AgentboxToolProperties properties = properties(server.baseUrl());
            AgentboxClient client = new AgentboxClient(properties, objectMapper, HttpClient.newHttpClient());
            SystemAgentboxStopSession tool = new SystemAgentboxStopSession(properties, client);

            JsonNode result = tool.invoke(Map.of("session_id", "demo-session"));

            assertThat(result.path("session_id").asText()).isEqualTo("demo-session");
            assertThat(result.path("status").asText()).isEqualTo("stopped");
        }
    }

    @Test
    void executeShouldReportTimeout() throws Exception {
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, """
                    {"session_id":"late-session","created":true}
                    """);
        })) {
            AgentboxToolProperties properties = properties(server.baseUrl());
            properties.setRequestTimeoutMs(50);
            AgentboxClient client = new AgentboxClient(properties, objectMapper, HttpClient.newHttpClient());
            SystemAgentboxExecute tool = new SystemAgentboxExecute(properties, client);

            JsonNode result = tool.invoke(Map.of("command", "/bin/sh"));

            assertThat(result.path("ok").asBoolean()).isFalse();
            assertThat(result.path("error").asText()).contains("timed out");
        }
    }

    private AgentboxToolProperties properties(String baseUrl) {
        AgentboxToolProperties properties = new AgentboxToolProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setDefaultRuntime("busybox");
        properties.setDefaultVersion("latest");
        properties.setDefaultCwd("/workspace");
        properties.setRequestTimeoutMs(1000);
        return properties;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer(Handler handler) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/execute", exchange -> handler.handle(exchange));
            this.server.createContext("/session/stop", exchange -> handler.handle(exchange));
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
