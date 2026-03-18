package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.tool.ContainerHubClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerHubSandboxServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveLevelShouldUseAgentConfigWhenPresent() {
        ContainerHubToolProperties properties = containerHubProperties("run");
        ContainerHubSandboxService service = createService(properties);

        AgentDefinition definition = definitionWithLevel(SandboxLevel.AGENT);
        assertThat(service.resolveLevel(definition)).isEqualTo(SandboxLevel.AGENT);
    }

    @Test
    void resolveLevelShouldFallbackToGlobalDefault() {
        ContainerHubToolProperties properties = containerHubProperties("global");
        ContainerHubSandboxService service = createService(properties);

        AgentDefinition definition = definitionWithLevel(null);
        assertThat(service.resolveLevel(definition)).isEqualTo(SandboxLevel.GLOBAL);
    }

    @Test
    void resolveLevelShouldDefaultToRunWhenNothingConfigured() {
        ContainerHubToolProperties properties = containerHubProperties(null);
        ContainerHubSandboxService service = createService(properties);

        AgentDefinition definition = definitionWithLevel(null);
        assertThat(service.resolveLevel(definition)).isEqualTo(SandboxLevel.RUN);
    }

    @Test
    void requiresSandboxShouldReturnTrueWhenToolPresent() {
        ContainerHubSandboxService service = createService(containerHubProperties("run"));
        AgentDefinition definition = definitionWithLevel(null);
        assertThat(service.requiresSandbox(definition)).isTrue();
    }

    @Test
    void requiresSandboxShouldReturnFalseWhenNoTool() {
        ContainerHubSandboxService service = createService(containerHubProperties("run"));
        AgentDefinition definition = new AgentDefinition(
                "no-sandbox",
                "no-sandbox",
                null,
                "demo",
                "role",
                null,
                "bailian",
                "qwen3-max",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, Budget.DEFAULT),
                new OneshotMode(new StageSettings("sys", null, null, List.of(), false, ComputePolicy.MEDIUM), null, null),
                List.of(),
                List.of(),
                new AgentDefinition.SandboxConfig(null, null),
                List.of()
        );
        assertThat(service.requiresSandbox(definition)).isFalse();
    }

    @Test
    void runLevelShouldCreateAndDestroySession() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        service.openIfNeeded(context);

        assertThat(context.sandboxSession()).isNotNull();
        assertThat(context.sandboxSession().level()).isEqualTo(SandboxLevel.RUN);
        assertThat(context.sandboxSession().sessionId()).startsWith("run-");
        assertThat(events).contains("createSession");

        service.closeQuietly(context);
        assertThat(context.sandboxSession()).isNull();
        // destroy is async, so we can't assert immediate stop
    }

    @Test
    void agentLevelShouldReuseSession() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        AgentDefinition definition = definitionWithLevel(SandboxLevel.AGENT);

        // First run
        ExecutionContext ctx1 = createContext(definition, "chat1", "run1");
        service.openIfNeeded(ctx1);
        assertThat(ctx1.sandboxSession()).isNotNull();
        assertThat(ctx1.sandboxSession().level()).isEqualTo(SandboxLevel.AGENT);
        String sessionId1 = ctx1.sandboxSession().sessionId();

        // Second run - should reuse session
        ExecutionContext ctx2 = createContext(definition, "chat2", "run2");
        service.openIfNeeded(ctx2);
        assertThat(ctx2.sandboxSession()).isNotNull();
        assertThat(ctx2.sandboxSession().sessionId()).isEqualTo(sessionId1);

        // Only one createSession call
        long createCount = events.stream().filter("createSession"::equals).count();
        assertThat(createCount).isEqualTo(1);

        service.closeQuietly(ctx1);
        service.closeQuietly(ctx2);
    }

    @Test
    void globalLevelShouldCreateSingletonSession() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        AgentDefinition def1 = definitionWithLevel(SandboxLevel.GLOBAL, "agent-a");
        AgentDefinition def2 = definitionWithLevel(SandboxLevel.GLOBAL, "agent-b");

        ExecutionContext ctx1 = createContext(def1, "chat1", "run1");
        service.openIfNeeded(ctx1);
        assertThat(ctx1.sandboxSession()).isNotNull();
        assertThat(ctx1.sandboxSession().level()).isEqualTo(SandboxLevel.GLOBAL);
        assertThat(ctx1.sandboxSession().sessionId()).isEqualTo("global-singleton");

        ExecutionContext ctx2 = createContext(def2, "chat2", "run2");
        service.openIfNeeded(ctx2);
        assertThat(ctx2.sandboxSession().sessionId()).isEqualTo("global-singleton");

        // Only one createSession call for global
        long createCount = events.stream().filter("createSession"::equals).count();
        assertThat(createCount).isEqualTo(1);

        // closeQuietly should be no-op for global
        service.closeQuietly(ctx1);
        service.closeQuietly(ctx2);
    }

    @Test
    void destroyShouldStopAllSessions() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        // Create agent session
        AgentDefinition agentDef = definitionWithLevel(SandboxLevel.AGENT, "agent-destroy-test");
        ExecutionContext agentCtx = createContext(agentDef, "chat1", "run1");
        service.openIfNeeded(agentCtx);

        // Create global session
        AgentDefinition globalDef = definitionWithLevel(SandboxLevel.GLOBAL, "global-destroy-test");
        ExecutionContext globalCtx = createContext(globalDef, "chat2", "run2");
        service.openIfNeeded(globalCtx);

        events.clear();
        service.destroy();

        long stopCount = events.stream().filter("stopSession"::equals).count();
        assertThat(stopCount).isEqualTo(2); // one agent + one global
    }

    @Test
    void openShouldFailWhenCreateSessionReturnsError() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events) {
            @Override
            public JsonNode createSession(ObjectNode payload) {
                events.add("createSession");
                ObjectNode error = ExecutionContext.OBJECT_MAPPER.createObjectNode();
                error.put("ok", false);
                error.put("error", "sandbox unavailable");
                return error;
            }
        };
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        assertThatThrownBy(() -> service.openIfNeeded(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub sandbox create failed");
    }

    private ContainerHubToolProperties containerHubProperties(String defaultLevel) {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://container-hub.test");
        properties.setDefaultEnvironmentId("shell");
        properties.setRequestTimeoutMs(1000);
        if (defaultLevel != null) {
            properties.setDefaultSandboxLevel(defaultLevel);
        }
        properties.setDestroyQueueDelayMs(10);
        properties.setAgentIdleTimeoutMs(100);
        return properties;
    }

    private ContainerHubSandboxService createService(ContainerHubToolProperties properties) {
        StubContainerHubClient client = new StubContainerHubClient(new CopyOnWriteArrayList<>());
        ContainerHubMountResolver mountResolver = new ContainerHubMountResolver(properties, null, null);
        return new ContainerHubSandboxService(properties, client, mountResolver);
    }

    private AgentDefinition definitionWithLevel(SandboxLevel level) {
        return definitionWithLevel(level, "sandbox-agent");
    }

    private AgentDefinition definitionWithLevel(SandboxLevel level, String agentKey) {
        return new AgentDefinition(
                agentKey,
                agentKey,
                null,
                "demo",
                "role",
                null,
                "bailian",
                "qwen3-max",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, Budget.DEFAULT),
                new OneshotMode(new StageSettings("sys", null, null, List.of("container_hub_bash"), false, ComputePolicy.MEDIUM), null, null),
                List.of("container_hub_bash"),
                List.of(),
                new AgentDefinition.SandboxConfig("shell", level),
                List.of()
        );
    }

    private ExecutionContext createContext(AgentDefinition definition) {
        return createContext(definition, "chat-1", "run-1");
    }

    private ExecutionContext createContext(AgentDefinition definition, String chatId, String runId) {
        AgentRequest request = new AgentRequest("test", chatId, "req-1", runId, Map.of());
        return new ExecutionContext(definition, request, List.of());
    }

    private static class StubContainerHubClient extends ContainerHubClient {
        protected final CopyOnWriteArrayList<String> events;

        StubContainerHubClient(CopyOnWriteArrayList<String> events) {
            super(stubProperties(), new ObjectMapper());
            this.events = events;
        }

        @Override
        public JsonNode createSession(ObjectNode payload) {
            events.add("createSession");
            ObjectNode response = ExecutionContext.OBJECT_MAPPER.createObjectNode();
            response.put("session_id", payload.path("session_id").asText());
            response.put("cwd", "/workspace");
            response.put("status", "running");
            return response;
        }

        @Override
        public JsonNode stopSession(String sessionId) {
            events.add("stopSession");
            ObjectNode response = ExecutionContext.OBJECT_MAPPER.createObjectNode();
            response.put("session_id", sessionId);
            response.put("status", "stopped");
            return response;
        }

        @Override
        public JsonNode executeSession(String sessionId, ObjectNode payload) {
            events.add("executeSession");
            ObjectNode response = ExecutionContext.OBJECT_MAPPER.createObjectNode();
            response.put("session_id", sessionId);
            response.put("exit_code", 0);
            response.put("stdout", "ok\n");
            response.put("stderr", "");
            return response;
        }

        private static ContainerHubToolProperties stubProperties() {
            ContainerHubToolProperties p = new ContainerHubToolProperties();
            p.setEnabled(true);
            p.setBaseUrl("http://stub.test");
            return p;
        }
    }
}
