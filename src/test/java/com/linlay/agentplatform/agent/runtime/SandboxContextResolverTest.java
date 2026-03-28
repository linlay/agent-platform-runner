package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.tool.ContainerHubClient;
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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SandboxContextResolverTest {

    @Test
    void resolveShouldBuildSandboxContextFromAgentAndProperties() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "daily-office",
                        true,
                        "You are running inside the `daily-office` environment.",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setDefaultEnvironmentId("shell");
        properties.setDefaultSandboxLevel("edit");
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, properties);

        RuntimeRequestContext.SandboxContext context = resolver.resolve(
                agentDefinition(
                        List.of("_sandbox_bash_"),
                        new AgentDefinition.SandboxConfig(
                                "daily-office",
                                SandboxLevel.RUN,
                                List.of(new AgentDefinition.ExtraMount("tools", null, null, MountAccessMode.RO))
                        )
                ),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        );

        assertThat(context.environmentId()).isEqualTo("daily-office");
        assertThat(context.configuredEnvironmentId()).isEqualTo("daily-office");
        assertThat(context.defaultEnvironmentId()).isEqualTo("shell");
        assertThat(context.level()).isEqualTo("RUN");
        assertThat(context.containerHubEnabled()).isTrue();
        assertThat(context.usesContainerHubTool()).isTrue();
        assertThat(context.extraMounts()).containsExactly("platform:tools (ro)");
        assertThat(context.environmentPrompt()).contains("daily-office");
    }

    @Test
    void resolveShouldUseDefaultEnvironmentAndLevelWhenAgentDoesNotOverrideThem() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("shell")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "shell",
                        true,
                        "Shell sandbox prompt",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setDefaultEnvironmentId("shell");
        properties.setDefaultSandboxLevel("edit");
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, properties);

        RuntimeRequestContext.SandboxContext context = resolver.resolve(
                agentDefinition(List.of("_sandbox_bash_"), new AgentDefinition.SandboxConfig(null)),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        );

        assertThat(context.environmentId()).isEqualTo("shell");
        assertThat(context.configuredEnvironmentId()).isNull();
        assertThat(context.defaultEnvironmentId()).isEqualTo("shell");
        assertThat(context.level()).isEqualTo("EDIT");
        assertThat(context.usesContainerHubTool()).isTrue();
        assertThat(context.environmentPrompt()).isEqualTo("Shell sandbox prompt");
    }

    @Test
    void resolveShouldAcceptShellWhenPromptIsMissing(CapturedOutput output) {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("shell")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "shell",
                        false,
                        "",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setDefaultEnvironmentId("shell");
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, properties);

        RuntimeRequestContext.SandboxContext context = resolver.resolve(
                agentDefinition(List.of("_sandbox_bash_"), new AgentDefinition.SandboxConfig("shell")),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        );

        assertThat(context.environmentId()).isEqualTo("shell");
        assertThat(context.environmentPrompt()).isEmpty();
        assertThat(output.getAll()).contains("Sandbox agent prompt accepted without content");
        assertThat(output.getAll()).contains("reason=shell_prompt_optional");
    }

    @Test
    void resolveShouldFailWhenNonShellPromptIsMissing() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "daily-office",
                        false,
                        "",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, new ContainerHubToolProperties());

        assertThatThrownBy(() -> resolver.resolve(
                agentDefinition(List.of("_sandbox_bash_"), new AgentDefinition.SandboxConfig("daily-office")),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox context requires a non-empty environment prompt for 'daily-office'");
    }

    @Test
    void resolveShouldFailWhenNonShellPromptIsBlank() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "daily-office",
                        true,
                        "",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, new ContainerHubToolProperties());

        assertThatThrownBy(() -> resolver.resolve(
                agentDefinition(List.of("_sandbox_bash_"), new AgentDefinition.SandboxConfig("daily-office")),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox context requires a non-blank environment prompt for 'daily-office'");
    }

    @Test
    void resolveShouldFailWhenPromptFetchFailsAndLogReason(CapturedOutput output) {
        RecordingHttpClient httpClient = new RecordingHttpClient();
        httpClient.register("/api/environments/daily-office/agent-prompt", request -> new StubHttpResponse(request, 503, """
                {"error":"hub unavailable"}
                """));
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setBaseUrl("http://container-hub.test");
        properties.setRequestTimeoutMs(1000);
        ContainerHubClient containerHubClient = new ContainerHubClient(properties, new ObjectMapper(), httpClient);
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, new ContainerHubToolProperties());

        assertThatThrownBy(() -> resolver.resolve(
                agentDefinition(List.of("_sandbox_bash_"), new AgentDefinition.SandboxConfig("daily-office")),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox context failed to load environment prompt");
        assertThat(output.getAll()).contains("container-hub.request operation=container_hub_get_environment_agent_prompt");
        assertThat(output.getAll()).contains("url=http://container-hub.test/api/environments/daily-office/agent-prompt");
        assertThat(output.getAll()).contains("container-hub.response operation=container_hub_get_environment_agent_prompt");
        assertThat(output.getAll()).contains("status=503");
        assertThat(output.getAll()).contains("Sandbox agent prompt fetch failed");
        assertThat(output.getAll()).contains("environmentId=daily-office");
    }

    private AgentDefinition agentDefinition(List<String> tools, AgentDefinition.SandboxConfig sandboxConfig) {
        return new AgentDefinition(
                "demo-agent",
                "Demo Agent",
                null,
                "Demo Description",
                "Demo Role",
                "model-key",
                "provider",
                "model-id",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.NONE, Budget.DEFAULT),
                new OneshotMode(new StageSettings("prompt", null, null, tools, false, ComputePolicy.MEDIUM), null, null),
                tools,
                List.of("docx"),
                List.of(),
                sandboxConfig,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                null
        );
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
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.of(java.time.Duration.ofSeconds(1));
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
