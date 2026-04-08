package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.engine.sandbox.ContainerHubClient;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ContainerHubClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoadEnvironmentAgentPrompt(CapturedOutput output) {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/environments/daily-office/agent-prompt", request -> new StubHttpResponse(request, 200, """
                {"environment_name":"daily-office","has_prompt":true,"prompt":"hello sandbox","updated_at":"2026-03-22T10:15:30Z"}
                """, "application/json"));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("daily-office");

        assertThat(result.ok()).isTrue();
        assertThat(result.hasPrompt()).isTrue();
        assertThat(result.environmentName()).isEqualTo("daily-office");
        assertThat(result.prompt()).isEqualTo("hello sandbox");
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-03-22T10:15:30Z"));
        assertThat(output.getAll()).contains("container-hub.request operation=container_hub_get_environment_agent_prompt");
        assertThat(output.getAll()).contains("method=GET");
        assertThat(output.getAll()).contains("url=http://container-hub.test/api/environments/daily-office/agent-prompt");
        assertThat(output.getAll()).contains("container-hub.response operation=container_hub_get_environment_agent_prompt");
        assertThat(output.getAll()).contains("status=200");
        assertThat(output.getAll()).contains("\"has_prompt\":true");
    }

    @Test
    void shouldSurfaceEnvironmentAgentPromptErrors(CapturedOutput output) {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/environments/daily-office/agent-prompt", request -> new StubHttpResponse(request, 503, """
                {"error":"hub unavailable"}
                """, "application/json"));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("daily-office");

        assertThat(result.ok()).isFalse();
        assertThat(result.hasPrompt()).isFalse();
        assertThat(result.error()).contains("hub unavailable");
        assertThat(output.getAll()).contains("container-hub.response operation=container_hub_get_environment_agent_prompt");
        assertThat(output.getAll()).contains("status=503");
        assertThat(output.getAll()).contains("hub unavailable");
    }

    @Test
    void shouldTreatBlankEnvironmentNameAsFailure() {
        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, new RecordingHttpClient());

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("   ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("environment name is required");
    }

    @Test
    void shouldLogCreateSessionRequestAndMaskSensitiveValues(CapturedOutput output) {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/create", request -> new StubHttpResponse(request, 200, """
                {"ok":true,"session_id":"run-chat-1","cwd":"/workspace"}
                """, "application/json"));

        ContainerHubToolProperties properties = properties("http://container-hub.test");
        properties.setAuthToken("super-secret-token");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, httpClient);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("session_id", "run-chat-1");
        payload.put("environment_name", "shell");
        payload.put("token", "payload-secret");

        JsonNode response = client.createSession(payload);

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(output.getAll()).contains("container-hub.request operation=container_hub_create_session");
        assertThat(output.getAll()).contains("method=POST");
        assertThat(output.getAll()).contains("url=http://container-hub.test/api/sessions/create");
        assertThat(output.getAll()).contains("authPresent=true");
        assertThat(output.getAll()).contains("\"token\":\"***\"");
        assertThat(output.getAll()).contains("container-hub.response operation=container_hub_create_session");
        assertThat(output.getAll()).contains("\"session_id\":\"run-chat-1\"");
        assertThat(output.getAll()).doesNotContain("super-secret-token");
        assertThat(output.getAll()).doesNotContain("payload-secret");
    }

    @Test
    void shouldLogTransportErrors(CapturedOutput output) {
        ContainerHubToolProperties properties = properties("http://container-hub.test");
        properties.setAuthToken("super-secret-token");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, new RecordingHttpClient());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("session_id", "run-chat-1");
        payload.put("environment_name", "shell");

        JsonNode response = client.createSession(payload);

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("error").asText()).contains("No handler for path /api/sessions/create");
        assertThat(output.getAll()).contains("container-hub.error operation=container_hub_create_session");
        assertThat(output.getAll()).contains("url=http://container-hub.test/api/sessions/create");
        assertThat(output.getAll()).doesNotContain("super-secret-token");
    }

    @Test
    void executeSessionShouldTreatTextPlainResponseAsTextNode() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/run-run1/execute", request -> new StubHttpResponse(
                request,
                200,
                "total 4\ndrwxr-xr-x 2 root root 64 Apr 8 06:44 .\nhello\n",
                "text/plain; charset=utf-8"
        ));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("command", "/bin/sh");

        JsonNode response = client.executeSession("run-run1", payload);

        assertThat(response.isTextual()).isTrue();
        assertThat(response.asText()).contains("hello");
    }

    @Test
    void executeSessionShouldKeepBashErrorJsonResponseWhenContentTypeIsJson() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/run-run1/execute", request -> new StubHttpResponse(
                request,
                200,
                "{\"exit_code\":2,\"stdout\":\"partial output\\n\",\"stderr\":\"command failed\\n\"}",
                "application/json"
        ));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("command", "/bin/sh");

        JsonNode response = client.executeSession("run-run1", payload);

        assertThat(response.isObject()).isTrue();
        assertThat(response.path("exit_code").asInt()).isEqualTo(2);
        assertThat(response.path("stdout").asText()).isEqualTo("partial output\n");
        assertThat(response.path("stderr").asText()).isEqualTo("command failed\n");
    }

    private ContainerHubToolProperties properties(String baseUrl) {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setRequestTimeoutMs(1000);
        return properties;
    }

    @FunctionalInterface
    private interface Handler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final Map<String, Handler> handlers = new java.util.HashMap<>();

        private void register(String path, Handler handler) {
            handlers.put(path, handler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            Handler handler = handlers.get(request.uri().getPath());
            if (handler == null) {
                throw new IOException("No handler for path " + request.uri().getPath());
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) handler.handle(request);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record StubHttpResponse(
            HttpRequest request,
            int statusCode,
            String body,
            String contentType
    ) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            if (contentType == null || contentType.isBlank()) {
                return HttpHeaders.of(Map.of(), (left, right) -> true);
            }
            return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (left, right) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
