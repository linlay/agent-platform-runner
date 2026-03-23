package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.PanProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.config.ViewportServerProperties;
import com.linlay.agentplatform.config.RootProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
        RecordingStubContainerHubClient client = new RecordingStubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        service.openIfNeeded(context);

        assertThat(context.sandboxSession()).isNotNull();
        assertThat(context.sandboxSession().level()).isEqualTo(SandboxLevel.RUN);
        assertThat(context.sandboxSession().sessionId()).startsWith("run-");
        assertThat(events).contains("createSession");
        assertThat(client.lastCreatePayload).isNotNull();
        assertThat(client.lastCreatePayload.path("cwd").asText()).isEqualTo("/workspace");
        assertThat(client.lastCreatePayload.path("mounts").isArray()).isTrue();
        JsonNode mounts = client.lastCreatePayload.path("mounts");
        assertThat(mounts).isNotEmpty();
        assertThat(mounts.get(0).path("source").asText()).isNotBlank();
        assertThat(mounts.get(0).path("destination").asText()).isEqualTo("/workspace");
        assertThat(mounts.get(0).path("read_only").asBoolean()).isFalse();
        assertThat(mounts.get(0).has("host_path")).isFalse();
        assertThat(mounts.get(0).has("container_path")).isFalse();
        assertThat(findMount(mounts, "/root").path("read_only").asBoolean()).isFalse();
        assertThat(findMount(mounts, "/pan").path("read_only").asBoolean()).isFalse();
        assertThat(findMount(mounts, "/skills").path("read_only").asBoolean()).isTrue();
        assertThat(context.sandboxSession().defaultCwd()).isEqualTo("/workspace");

        service.closeQuietly(context);
        assertThat(context.sandboxSession()).isNull();
        // destroy is async, so we can't assert immediate stop
    }

    @Test
    void agentLevelShouldReuseSession() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        AgentDefinition definition = definitionWithLevel(SandboxLevel.AGENT);

        // First run
        ExecutionContext ctx1 = createContext(definition, "chat1", "run1");
        service.openIfNeeded(ctx1);
        assertThat(ctx1.sandboxSession()).isNotNull();
        assertThat(ctx1.sandboxSession().level()).isEqualTo(SandboxLevel.AGENT);
        assertThat(ctx1.sandboxSession().defaultCwd()).isEqualTo("/workspace/chat1");
        String sessionId1 = ctx1.sandboxSession().sessionId();

        // Second run - should reuse session
        ExecutionContext ctx2 = createContext(definition, "chat2", "run2");
        service.openIfNeeded(ctx2);
        assertThat(ctx2.sandboxSession()).isNotNull();
        assertThat(ctx2.sandboxSession().sessionId()).isEqualTo(sessionId1);
        assertThat(ctx2.sandboxSession().defaultCwd()).isEqualTo("/workspace/chat2");

        // Only one createSession call
        long createCount = events.stream().filter("createSession"::equals).count();
        assertThat(createCount).isEqualTo(1);

        service.closeQuietly(ctx1);
        service.closeQuietly(ctx2);
    }

    @Test
    void agentLevelShouldMountAgentLocalSkillsDirectory() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        RecordingStubContainerHubClient client = new RecordingStubContainerHubClient(events);
        AgentProperties agentProperties = new AgentProperties();
        Path agentsRoot = createTempMountDir("container-hub-agents");
        agentProperties.setExternalDir(agentsRoot.toString());
        Path agentSkillsDir = Files.createDirectories(agentsRoot.resolve("sandbox-agent").resolve("skills"));
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null, null, null, agentProperties);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.AGENT));
        service.openIfNeeded(context);

        JsonNode mounts = client.lastCreatePayload.path("mounts");
        assertThat(findMount(mounts, "/skills").path("source").asText())
                .isEqualTo(agentSkillsDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void globalLevelShouldCreateSingletonSession() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        AgentDefinition def1 = definitionWithLevel(SandboxLevel.GLOBAL, "agent-a");
        AgentDefinition def2 = definitionWithLevel(SandboxLevel.GLOBAL, "agent-b");

        ExecutionContext ctx1 = createContext(def1, "chat1", "run1");
        service.openIfNeeded(ctx1);
        assertThat(ctx1.sandboxSession()).isNotNull();
        assertThat(ctx1.sandboxSession().level()).isEqualTo(SandboxLevel.GLOBAL);
        assertThat(ctx1.sandboxSession().sessionId()).isEqualTo("global-singleton");
        assertThat(ctx1.sandboxSession().defaultCwd()).isEqualTo("/workspace/chat1");

        ExecutionContext ctx2 = createContext(def2, "chat2", "run2");
        service.openIfNeeded(ctx2);
        assertThat(ctx2.sandboxSession().sessionId()).isEqualTo("global-singleton");
        assertThat(ctx2.sandboxSession().defaultCwd()).isEqualTo("/workspace/chat2");

        // Only one createSession call for global
        long createCount = events.stream().filter("createSession"::equals).count();
        assertThat(createCount).isEqualTo(1);

        // closeQuietly should be no-op for global
        service.closeQuietly(ctx1);
        service.closeQuietly(ctx2);
    }

    @Test
    void globalLevelShouldMountSkillsMarketDirectory() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        RecordingStubContainerHubClient client = new RecordingStubContainerHubClient(events);
        Path skillsMarketDir = createTempMountDir("container-hub-skills-market");
        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir(skillsMarketDir.toString());
        AgentProperties agentProperties = new AgentProperties();
        Path agentsRoot = createTempMountDir("container-hub-agents");
        agentProperties.setExternalDir(agentsRoot.toString());
        Files.createDirectories(agentsRoot.resolve("agent-a").resolve("skills"));
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, skillProperties, null, null, agentProperties);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.GLOBAL, "agent-a"));
        service.openIfNeeded(context);

        JsonNode mounts = client.lastCreatePayload.path("mounts");
        assertThat(findMount(mounts, "/skills").path("source").asText())
                .isEqualTo(skillsMarketDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void destroyShouldStopAllSessions() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
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
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        assertThatThrownBy(() -> service.openIfNeeded(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub sandbox create failed");
    }

    @Test
    void openShouldFailBeforeCreateWhenConfiguredRootDirDoesNotExist() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        RootProperties rootProperties = new RootProperties();
        rootProperties.setExternalDir("/path/that/does/not/exist");
        StubContainerHubClient client = new StubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null, rootProperties, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        assertThatThrownBy(() -> service.openIfNeeded(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for root-dir")
                .hasMessageContaining("resolved=/path/that/does/not/exist")
                .hasMessageContaining("containerPath=/root");
        assertThat(events).isEmpty();
    }

    @Test
    void runLevelShouldSerializeOverriddenDefaultMountModes() {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        RecordingStubContainerHubClient client = new RecordingStubContainerHubClient(events);
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        AgentDefinition definition = definitionWithLevel(
                SandboxLevel.RUN,
                "sandbox-agent",
                List.of(new AgentDefinition.ExtraMount(null, null, "/skills", MountAccessMode.RW))
        );

        ExecutionContext context = createContext(definition);
        service.openIfNeeded(context);

        JsonNode mounts = client.lastCreatePayload.path("mounts");
        assertThat(findMount(mounts, "/skills").path("read_only").asBoolean()).isFalse();
    }

    @Test
    void runLevelShouldUseDotEnvHostPathsInCreatePayload() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ContainerHubToolProperties properties = containerHubProperties("run");
        RecordingStubContainerHubClient client = new RecordingStubContainerHubClient(events);

        Path hostChatsDir = createTempMountDir("container-hub-host-chats");
        Path hostRootDir = createTempMountDir("container-hub-host-root");
        Path hostPanDir = createTempMountDir("container-hub-host-pan");
        Path hostSkillsDir = createTempMountDir("container-hub-host-skills");
        Path hostAgentsDir = createTempMountDir("container-hub-host-agents");
        Files.createDirectories(hostAgentsDir.resolve("sandbox-agent").resolve("skills"));

        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir("/opt/runtime/chats");
        RootProperties rootProperties = new RootProperties();
        rootProperties.setExternalDir("/opt/runtime/root");
        PanProperties panProperties = new PanProperties();
        panProperties.setExternalDir("/opt/runtime/pan");
        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir("/opt/runtime/skills-market");
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir("/opt/runtime/agents");

        ContainerHubMountResolver mountResolver = containerHubMountResolver(
                properties,
                dataProperties,
                skillProperties,
                rootProperties,
                panProperties,
                agentProperties,
                Map.of(
                        "CHATS_DIR", hostChatsDir.toString(),
                        "ROOT_DIR", hostRootDir.toString(),
                        "PAN_DIR", hostPanDir.toString(),
                        "SKILLS_MARKET_DIR", hostSkillsDir.toString(),
                        "AGENTS_DIR", hostAgentsDir.toString()
                )
        );
        ContainerHubSandboxService service = new ContainerHubSandboxService(properties, client, mountResolver);

        ExecutionContext context = createContext(definitionWithLevel(SandboxLevel.RUN));
        service.openIfNeeded(context);

        JsonNode mounts = client.lastCreatePayload.path("mounts");
        assertThat(findMount(mounts, "/workspace").path("source").asText())
                .isEqualTo(hostChatsDir.resolve("chat-1").toAbsolutePath().normalize().toString());
        assertThat(findMount(mounts, "/root").path("source").asText())
                .isEqualTo(hostRootDir.toAbsolutePath().normalize().toString());
        assertThat(findMount(mounts, "/pan").path("source").asText())
                .isEqualTo(hostPanDir.toAbsolutePath().normalize().toString());
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

    private Path createTempMountDir(String prefix) {
        try {
            return java.nio.file.Files.createTempDirectory(prefix);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to create temp mount dir for test", ex);
        }
    }

    private ContainerHubSandboxService createService(ContainerHubToolProperties properties) {
        StubContainerHubClient client = new StubContainerHubClient(new CopyOnWriteArrayList<>());
        ContainerHubMountResolver mountResolver = containerHubMountResolver(properties, null, null);
        return new ContainerHubSandboxService(properties, client, mountResolver);
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties
    ) {
        return containerHubMountResolver(properties, dataProperties, skillProperties, null, null);
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties
    ) {
        return containerHubMountResolver(properties, dataProperties, skillProperties, rootProperties, panProperties, null);
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            AgentProperties agentProperties
    ) {
        return containerHubMountResolver(
                properties,
                dataProperties,
                skillProperties,
                rootProperties,
                panProperties,
                agentProperties,
                Map.of()
        );
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            AgentProperties agentProperties,
            Map<String, String> hostRuntimeDirOverrides
    ) {
        RootProperties resolvedRootProperties = rootProperties == null ? new RootProperties() : rootProperties;
        if (rootProperties == null) {
            resolvedRootProperties.setExternalDir(createTempMountDir("container-hub-root").toString());
        }

        PanProperties resolvedPanProperties = panProperties == null ? new PanProperties() : panProperties;
        if (panProperties == null) {
            resolvedPanProperties.setExternalDir(createTempMountDir("container-hub-pan").toString());
        }

        SkillProperties resolvedSkillProperties = skillProperties == null ? new SkillProperties() : skillProperties;
        if (skillProperties == null) {
            resolvedSkillProperties.setExternalDir(createTempMountDir("container-hub-skills").toString());
        }

        AgentProperties resolvedAgentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        if (agentProperties == null) {
            resolvedAgentProperties.setExternalDir(createTempMountDir("container-hub-agents").toString());
        }

        return new ContainerHubMountResolver(
                dataProperties,
                resolvedRootProperties,
                resolvedPanProperties,
                resolvedSkillProperties,
                new ToolProperties(),
                resolvedAgentProperties,
                new ModelProperties(),
                new ViewportProperties(),
                new ViewportServerProperties(),
                new TeamProperties(),
                new ScheduleProperties(),
                new McpProperties(),
                new ProviderProperties(),
                new RuntimeDirectoryHostPaths(hostRuntimeDirOverrides)
        );
    }

    private AgentDefinition definitionWithLevel(SandboxLevel level) {
        return definitionWithLevel(level, "sandbox-agent");
    }

    private AgentDefinition definitionWithLevel(SandboxLevel level, String agentKey) {
        return definitionWithLevel(level, agentKey, List.of());
    }

    private AgentDefinition definitionWithLevel(
            SandboxLevel level,
            String agentKey,
            List<AgentDefinition.ExtraMount> extraMounts
    ) {
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
                new OneshotMode(new StageSettings("sys", null, null, List.of("sandbox_bash"), false, ComputePolicy.MEDIUM), null, null),
                List.of("sandbox_bash"),
                List.of(),
                new AgentDefinition.SandboxConfig("shell", level, extraMounts),
                List.of()
        );
    }

    private JsonNode findMount(JsonNode mounts, String destination) {
        for (JsonNode mount : mounts) {
            if (destination.equals(mount.path("destination").asText())) {
                return mount;
            }
        }
        throw new IllegalStateException("missing mount for destination " + destination);
    }

    private ExecutionContext createContext(AgentDefinition definition) {
        return createContext(definition, "chat-1", "run-1");
    }

    private ExecutionContext createContext(AgentDefinition definition, String chatId, String runId) {
        AgentRequest request = new AgentRequest("test", chatId, "req-1", runId, Map.of());
        return executionContext(definition, request, List.of());
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

    private static final class RecordingStubContainerHubClient extends StubContainerHubClient {
        private ObjectNode lastCreatePayload;

        RecordingStubContainerHubClient(CopyOnWriteArrayList<String> events) {
            super(events);
        }

        @Override
        public JsonNode createSession(ObjectNode payload) {
            this.lastCreatePayload = payload.deepCopy();
            return super.createSession(payload);
        }
    }
}
