package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.mode.OneshotMode;
import com.linlay.agentplatform.engine.mode.StageSettings;
import com.linlay.agentplatform.engine.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.engine.sandbox.ContainerHubClient;
import com.linlay.agentplatform.engine.sandbox.SystemContainerHubBash;
import com.linlay.agentplatform.engine.policy.Budget;
import com.linlay.agentplatform.engine.policy.ComputePolicy;
import com.linlay.agentplatform.engine.policy.RunSpec;
import com.linlay.agentplatform.engine.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.chat.storage.ChatMessage;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerHubToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sandboxBashShouldExecuteAgainstRunScopedSession() throws Exception {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        AtomicReference<String> requestBody = new AtomicReference<>();
        httpClient.register("/api/sessions/run-run1/execute", request -> {
            requestBody.set(readBody(request));
            return new StubHttpResponse(request, 200, """
                    {"session_id":"run-run1","exit_code":0,"stdout":"/workspace\\nok\\n","stderr":"","timed_out":false}
                    """, "application/json");
        });

        ContainerHubToolProperties properties = properties("http://container-hub.test");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, httpClient);
        SystemContainerHubBash tool = new SystemContainerHubBash(properties, client);
        ExecutionContext context = executionContext(definition(), new AgentRequest("test", "chat1", "req1", "run1", Map.of()), List.of());
        context.bindSandboxSession(new ExecutionContext.SandboxSession("run-run1", "shell", "/workspace"));

        JsonNode result = tool.invoke(Map.of("command", "pwd && echo ok"), context);
        JsonNode request = objectMapper.readTree(requestBody.get());

        assertThat(request.path("command").asText()).isEqualTo("/bin/sh");
        assertThat(request.path("args")).isEqualTo(objectMapper.valueToTree(List.of("-lc", "pwd && echo ok")));
        assertThat(request.path("cwd").asText()).isEqualTo("/workspace");
        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("mode: sandbox");
        assertThat(result.asText()).contains("\"workingDirectory\": \"/workspace\"");
        assertThat(result.asText()).contains("/workspace");
        assertThat(result.asText()).contains("ok");
    }

    @Test
    void sandboxBashShouldSurfaceTransportErrorsAsTextResult() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/run-run1/execute", request -> new StubHttpResponse(request, 503, """
                {"error":"hub unavailable"}
                """, "application/json"));

        ContainerHubToolProperties properties = properties("http://container-hub.test");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, httpClient);
        SystemContainerHubBash tool = new SystemContainerHubBash(properties, client);
        ExecutionContext context = executionContext(definition(), new AgentRequest("test", "chat1", "req1", "run1", Map.of()), List.of());
        context.bindSandboxSession(new ExecutionContext.SandboxSession("run-run1", "shell", "/workspace"));

        JsonNode result = tool.invoke(Map.of("command", "pwd"), context);

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("hub unavailable");
    }

    @Test
    void sandboxBashShouldSupportPlainTextExecuteResponse() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/run-run1/execute", request -> new StubHttpResponse(
                request,
                200,
                "total 4\nhello\n",
                "text/plain"
        ));

        ContainerHubToolProperties properties = properties("http://container-hub.test");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, httpClient);
        SystemContainerHubBash tool = new SystemContainerHubBash(properties, client);
        ExecutionContext context = executionContext(definition(), new AgentRequest("test", "chat1", "req1", "run1", Map.of()), List.of());
        context.bindSandboxSession(new ExecutionContext.SandboxSession("run-run1", "shell", "/workspace"));

        JsonNode result = tool.invoke(Map.of("command", "ls -la && echo hello"), context);

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("stdout:");
        assertThat(result.asText()).contains("hello");
    }

    @Test
    void sandboxBashShouldSurfacePlainTextExecuteErrorsAsTextResult() {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/sessions/run-run1/execute", request -> new StubHttpResponse(
                request,
                503,
                "hub unavailable",
                "text/plain"
        ));

        ContainerHubToolProperties properties = properties("http://container-hub.test");
        ContainerHubClient client = new ContainerHubClient(properties, objectMapper, httpClient);
        SystemContainerHubBash tool = new SystemContainerHubBash(properties, client);
        ExecutionContext context = executionContext(definition(), new AgentRequest("test", "chat1", "req1", "run1", Map.of()), List.of());
        context.bindSandboxSession(new ExecutionContext.SandboxSession("run-run1", "shell", "/workspace"));

        JsonNode result = tool.invoke(Map.of("command", "pwd"), context);

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("hub unavailable");
    }

    private AgentDefinition definition() {
        return new AgentDefinition(
                "sandboxed",
                "sandboxed",
                null,
                "demo",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, Budget.DEFAULT),
                new OneshotMode(new StageSettings("sys", null, null, List.of("_sandbox_bash_"), false, ComputePolicy.MEDIUM), null, null),
                List.of("_sandbox_bash_"),
                List.of()
        );
    }

    private ContainerHubToolProperties properties(String baseUrl) {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setDefaultEnvironmentId("shell");
        properties.setRequestTimeoutMs(1000);
        return properties;
    }

    private ExecutionContext executionContext(
            AgentDefinition definition,
            AgentRequest request,
            List<ChatMessage> historyMessages
    ) {
        return ExecutionContext.builder(definition, request)
                .historyMessages(historyMessages)
                .build();
    }

    private static String readBody(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Throwable> error = new AtomicReference<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Handler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final java.util.Map<String, Handler> handlers = new java.util.HashMap<>();

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
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
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

    private static final class StubHttpResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;
        private final String contentType;

        private StubHttpResponse(HttpRequest request, int statusCode, String body, String contentType) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

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
            return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (a, b) -> true);
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
