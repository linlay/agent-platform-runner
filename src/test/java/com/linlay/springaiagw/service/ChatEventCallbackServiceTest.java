package com.linlay.springaiagw.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.config.ChatEventCallbackProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventCallbackServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPostSignedCallbackRequest() throws Exception {
        ArrayBlockingQueue<CapturedRequest> queue = new ArrayBlockingQueue<>(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/app/internal/chat-events", exchange -> {
            String body = readBody(exchange.getRequestBody());
            String timestamp = exchange.getRequestHeaders().getFirst("X-AGW-Timestamp");
            String signature = exchange.getRequestHeaders().getFirst("X-AGW-Signature");
            queue.offer(new CapturedRequest(body, timestamp, signature));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ChatEventCallbackProperties properties = new ChatEventCallbackProperties();
            properties.setEnabled(true);
            properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/app/internal/chat-events");
            properties.setSecret("test-secret");
            properties.setConnectTimeoutMs(500);
            properties.setRequestTimeoutMs(1000);

            ChatEventCallbackService service = new ChatEventCallbackService(properties, objectMapper);
            service.notifyNewContent(
                "123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001",
                "My Chat"
            );

            CapturedRequest captured = queue.poll(3, TimeUnit.SECONDS);
            assertThat(captured).isNotNull();
            assertThat(captured.timestamp).isNotBlank();
            assertThat(captured.signature).isNotBlank();

            JsonNode json = objectMapper.readTree(captured.body);
            assertThat(json.path("chatId").asText()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
            assertThat(json.path("runId").asText()).isEqualTo("123e4567-e89b-12d3-a456-426614174001");
            assertThat(json.path("chatName").asText()).isEqualTo("My Chat");
            assertThat(json.path("updatedAt").asLong()).isGreaterThan(0L);

            String expectedSignature = sign("test-secret", captured.timestamp, captured.body);
            assertThat(captured.signature).isEqualTo(expectedSignature);
        } finally {
            server.stop((int) Duration.ofMillis(1).toSeconds());
        }
    }

    @Test
    void shouldSkipWhenDisabled() throws Exception {
        ArrayBlockingQueue<CapturedRequest> queue = new ArrayBlockingQueue<>(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/app/internal/chat-events", exchange -> {
            queue.offer(new CapturedRequest("", "", ""));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ChatEventCallbackProperties properties = new ChatEventCallbackProperties();
            properties.setEnabled(false);
            properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/app/internal/chat-events");
            properties.setSecret("test-secret");

            ChatEventCallbackService service = new ChatEventCallbackService(properties, objectMapper);
            service.notifyNewContent(
                "123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001",
                "My Chat"
            );

            CapturedRequest captured = queue.poll(500, TimeUnit.MILLISECONDS);
            assertThat(captured).isNull();
        } finally {
            server.stop((int) Duration.ofMillis(1).toSeconds());
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private String sign(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private record CapturedRequest(String body, String timestamp, String signature) {
    }
}
