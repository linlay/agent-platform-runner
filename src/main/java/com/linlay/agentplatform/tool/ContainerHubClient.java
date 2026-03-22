package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public class ContainerHubClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final ContainerHubToolProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ContainerHubClient(ContainerHubToolProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build()
        );
    }

    public ContainerHubClient(ContainerHubToolProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public JsonNode createSession(ObjectNode payload) {
        return post("/api/sessions/create", payload, "container_hub_create_session");
    }

    public JsonNode executeSession(String sessionId, ObjectNode payload) {
        return post("/api/sessions/" + sessionId.trim() + "/execute", payload, "container_hub_execute");
    }

    public JsonNode stopSession(String sessionId) {
        return post("/api/sessions/" + sessionId.trim() + "/stop", objectMapper.createObjectNode(), "container_hub_stop_session");
    }

    public EnvironmentAgentPromptResult getEnvironmentAgentPrompt(String environmentName) {
        String normalizedEnvironmentName = environmentName == null ? "" : environmentName.trim();
        if (!StringUtils.hasText(normalizedEnvironmentName)) {
            return EnvironmentAgentPromptResult.failure("", "environment name is required");
        }
        String path = "/api/environments/" + normalizedEnvironmentName + "/agent-prompt";
        JsonNode response = get(path, "container_hub_get_environment_agent_prompt");
        if (response == null || !response.isObject()) {
            return EnvironmentAgentPromptResult.failure(normalizedEnvironmentName, "invalid agent prompt response");
        }
        if (response.path("ok").asBoolean(true) == false) {
            return EnvironmentAgentPromptResult.failure(normalizedEnvironmentName, extractError(response.path("body"), response.path("error").asText("")));
        }
        boolean hasPrompt = response.path("has_prompt").asBoolean(false);
        String prompt = response.path("prompt").asText("");
        String responseEnvironmentName = response.path("environment_name").asText(normalizedEnvironmentName);
        Instant updatedAt = parseInstant(response.path("updated_at").asText(null));
        return new EnvironmentAgentPromptResult(
                responseEnvironmentName,
                hasPrompt,
                StringUtils.hasText(prompt) ? prompt.trim() : "",
                updatedAt,
                null
        );
    }

    private JsonNode post(String path, ObjectNode payload, String operation) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            return transportError(operation, path, "Failed to serialize request: " + safeMessage(ex));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(path))
                .timeout(Duration.ofMillis(Math.max(1, properties.getRequestTimeoutMs())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (StringUtils.hasText(properties.getAuthToken())) {
            builder.header("Authorization", "Bearer " + properties.getAuthToken().trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = parseBody(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return body;
            }
            return errorResult(operation, path, response.statusCode(), extractError(body, response.body()), body, response.body());
        } catch (HttpTimeoutException ex) {
            return transportError(operation, path, "Request timed out after " + properties.getRequestTimeoutMs() + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return transportError(operation, path, "Request interrupted");
        } catch (IOException ex) {
            return transportError(operation, path, safeMessage(ex));
        }
    }

    private JsonNode get(String path, String operation) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(buildUri(path))
                .timeout(Duration.ofMillis(Math.max(1, properties.getRequestTimeoutMs())))
                .GET();
        if (StringUtils.hasText(properties.getAuthToken())) {
            builder.header("Authorization", "Bearer " + properties.getAuthToken().trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = parseBody(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return body;
            }
            return errorResult(operation, path, response.statusCode(), extractError(body, response.body()), body, response.body());
        } catch (HttpTimeoutException ex) {
            return transportError(operation, path, "Request timed out after " + properties.getRequestTimeoutMs() + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return transportError(operation, path, "Request interrupted");
        } catch (IOException ex) {
            return transportError(operation, path, safeMessage(ex));
        }
    }

    private URI buildUri(String path) {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + path);
    }

    private JsonNode parseBody(String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (IOException ignored) {
            return objectMapper.getNodeFactory().textNode(rawBody);
        }
    }

    private String extractError(JsonNode body, String rawBody) {
        if (body != null && body.isObject()) {
            for (String key : java.util.List.of("error", "message", "msg")) {
                JsonNode value = body.get(key);
                if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                    return value.asText();
                }
            }
        }
        if (StringUtils.hasText(rawBody)) {
            return rawBody.trim();
        }
        return "Container-hub request failed";
    }

    private JsonNode errorResult(String operation, String path, int status, String error, JsonNode body, String rawBody) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", false);
        result.put("operation", operation);
        result.put("endpoint", path);
        result.put("status", status);
        result.put("error", error);
        if (body != null && !body.isMissingNode() && !body.isNull()) {
            result.set("body", body);
        } else if (StringUtils.hasText(rawBody)) {
            result.put("bodyText", rawBody);
        }
        return result;
    }

    private JsonNode transportError(String operation, String path, String error) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", false);
        result.put("operation", operation);
        result.put("endpoint", path);
        result.put("error", error);
        return result;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }

    private Instant parseInstant(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    public record EnvironmentAgentPromptResult(
            String environmentName,
            boolean hasPrompt,
            String prompt,
            Instant updatedAt,
            String error
    ) {

        public static EnvironmentAgentPromptResult failure(String environmentName, String error) {
            return new EnvironmentAgentPromptResult(environmentName, false, "", null, StringUtils.hasText(error) ? error.trim() : "unknown error");
        }

        public boolean ok() {
            return !StringUtils.hasText(error);
        }
    }
}
