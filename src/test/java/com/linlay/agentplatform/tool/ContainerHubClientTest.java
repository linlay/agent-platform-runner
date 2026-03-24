package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import org.junit.jupiter.api.Test;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerHubClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoadEnvironmentAgentPrompt() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/environments/daily-office/agent-prompt", request -> new StubHttpResponse(request, 200, """
                {"environment_name":"daily-office","has_prompt":true,"prompt":"hello sandbox","updated_at":"2026-03-22T10:15:30Z"}
                """));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("daily-office");

        assertThat(result.ok()).isTrue();
        assertThat(result.hasPrompt()).isTrue();
        assertThat(result.environmentName()).isEqualTo("daily-office");
        assertThat(result.prompt()).isEqualTo("hello sandbox");
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-03-22T10:15:30Z"));
    }

    @Test
    void shouldSurfaceEnvironmentAgentPromptErrors() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/environments/daily-office/agent-prompt", request -> new StubHttpResponse(request, 503, """
                {"error":"hub unavailable"}
                """));

        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, httpClient);

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("daily-office");

        assertThat(result.ok()).isFalse();
        assertThat(result.hasPrompt()).isFalse();
        assertThat(result.error()).contains("hub unavailable");
    }

    @Test
    void shouldTreatBlankEnvironmentNameAsFailure() {
        ContainerHubClient client = new ContainerHubClient(properties("http://container-hub.test"), objectMapper, new RecordingHttpClient());

        ContainerHubClient.EnvironmentAgentPromptResult result = client.getEnvironmentAgentPrompt("   ");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("environment name is required");
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
            String body
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
            return HttpHeaders.of(Map.of(), (left, right) -> true);
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
