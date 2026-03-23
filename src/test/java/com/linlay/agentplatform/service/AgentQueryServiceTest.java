package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.RuntimeContextPromptService;
import com.linlay.agentplatform.config.FrontendToolProperties;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.agent.RuntimeContextTags;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.model.ViewportType;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AgentQueryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeEventShouldConvertHeartbeatCommentToEventWithoutData() {
        AgentQueryService service = newService();
        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .id("hb_1")
                .retry(Duration.ofSeconds(3))
                .build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);

        assertThat(normalized.event()).isEqualTo("heartbeat");
        assertThat(normalized.data()).isNull();
        assertThat(normalized.comment()).isNull();
        assertThat(normalized.id()).isEqualTo("hb_1");
        assertThat(normalized.retry()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void normalizeEventShouldKeepNonHeartbeatCommentUnchanged() {
        AgentQueryService service = newService();
        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .comment("keepalive")
                .build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);

        assertThat(normalized).isSameAs(event);
    }

    @Test
    void normalizeEventShouldKeepNormalDataEventUnchanged() {
        AgentQueryService service = newService();
        String payload = "{\"type\":\"content.delta\",\"delta\":\"hello\"}";
        ServerSentEvent<String> event = ServerSentEvent.builder(payload)
                .event("message")
                .build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);

        assertThat(normalized).isSameAs(event);
        assertThat(normalized.data()).isEqualTo(payload);
        assertThat(normalized.event()).isEqualTo("message");
    }

    @Test
    void normalizeEventShouldPreserveSeqForPlanUpdate() throws Exception {
        AgentQueryService service = newService();
        String payload = """
                {"seq":3,"type":"plan.update","planId":"plan_1","chatId":"chat_1","plan":[{"taskId":"task1","description":"d1","status":"init"}],"timestamp":1700000000000}
                """;
        ServerSentEvent<String> event = ServerSentEvent.builder(payload)
                .event("message")
                .build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);
        JsonNode node = objectMapper.readTree(normalized.data());

        assertThat(node.path("seq").asLong()).isEqualTo(3L);
        assertThat(node.path("type").asText()).isEqualTo("plan.update");
        assertThat(node.path("planId").asText()).isEqualTo("plan_1");
        assertThat(node.path("chatId").asText()).isEqualTo("chat_1");
        assertThat(node.path("plan").isArray()).isTrue();
        assertThat(node.path("timestamp").asLong()).isEqualTo(1700000000000L);
    }

    @Test
    void normalizeEventShouldInjectFrontendFieldsForToolStart() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(120_000);

        when(toolRegistry.descriptor("confirm_dialog")).thenReturn(Optional.of(frontendTool()));
        when(viewportRegistryService.find("confirm_dialog")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("confirm_dialog", ViewportType.HTML, "<div>ok</div>")
        ));

        AgentQueryService service = new AgentQueryService(
                null,
                null,
                objectMapper,
                null,
                toolRegistry,
                viewportRegistryService,
                frontendToolProperties,
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );
        ServerSentEvent<String> event = ServerSentEvent.builder("""
                {"type":"tool.start","toolName":"confirm_dialog","toolId":"call_1","runId":"run_1","toolLabel":"确认框","toolDescription":"confirm"}
                """).build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);
        JsonNode payload = objectMapper.readTree(normalized.data());

        assertThat(payload.path("type").asText()).isEqualTo("tool.start");
        assertThat(payload.path("toolLabel").asText()).isEqualTo("确认框");
        assertThat(payload.path("toolDescription").asText()).isEqualTo("confirm");
        assertThat(payload.path("toolType").asText()).isEqualTo("html");
        assertThat(payload.path("viewportKey").asText()).isEqualTo("confirm_dialog");
        assertThat(payload.path("toolTimeout").asLong()).isEqualTo(120_000L);
        assertThat(payload.has("toolParams")).isFalse();
    }

    @Test
    void normalizeEventShouldInjectFrontendFieldsForToolSnapshot() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(30_000);

        when(toolRegistry.descriptor("confirm_dialog")).thenReturn(Optional.of(frontendTool()));
        when(viewportRegistryService.find("confirm_dialog")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("confirm_dialog", ViewportType.QLC, Map.of("schema", Map.of()))
        ));

        AgentQueryService service = new AgentQueryService(
                null,
                null,
                objectMapper,
                null,
                toolRegistry,
                viewportRegistryService,
                frontendToolProperties,
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );
        ServerSentEvent<String> event = ServerSentEvent.builder("""
                {"type":"tool.snapshot","toolName":"confirm_dialog","toolId":"call_2","runId":"run_2","toolLabel":"确认框","toolDescription":"confirm"}
                """).build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);
        JsonNode payload = objectMapper.readTree(normalized.data());

        assertThat(payload.path("type").asText()).isEqualTo("tool.snapshot");
        assertThat(payload.path("toolLabel").asText()).isEqualTo("确认框");
        assertThat(payload.path("toolDescription").asText()).isEqualTo("confirm");
        assertThat(payload.path("toolType").asText()).isEqualTo("html");
        assertThat(payload.path("viewportKey").asText()).isEqualTo("confirm_dialog");
        assertThat(payload.path("toolTimeout").asLong()).isEqualTo(30_000L);
        assertThat(payload.has("toolParams")).isFalse();
    }

    @Test
    void prepareShouldPreferBoundAgentWhenChatAlreadyBound() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent boundAgent = mock(Agent.class);
        when(boundAgent.id()).thenReturn("bound-agent");
        when(boundAgent.name()).thenReturn("Bound Agent");
        when(agentRegistry.get("bound-agent")).thenReturn(boundAgent);

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.of("bound-agent"));
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "bound-agent", "Bound Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "hello", "bound-agent", null, 1L, 2L, "", "", 1, 2L, false));

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "request-agent", null, "user", "hello", null, null, null, true);
        AgentQueryService.QuerySession session = service.prepare(request);

        assertThat(session.agent().id()).isEqualTo("bound-agent");
        assertThat(session.request().agentKey()).isEqualTo("bound-agent");
        assertThat(session.agentRequest().query()).containsEntry("agentKey", "bound-agent");
    }

    @Test
    void prepareShouldPreserveHiddenFlagInRequestAndSnapshot() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent agent = mock(Agent.class);
        when(agent.id()).thenReturn("demo-agent");
        when(agent.name()).thenReturn("Demo Agent");
        when(agentRegistry.get("demo-agent")).thenReturn(agent);

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "demo-agent", "Demo Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "hello", "demo-agent", null, 1L, 2L, "", "", 1, 2L, false));

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "demo-agent", null, "user", "hello", null, null, null, true, true);
        AgentQueryService.QuerySession session = service.prepare(request);

        assertThat(session.request().hidden()).isTrue();
        assertThat(session.agentRequest().query()).containsEntry("hidden", true);
    }

    @Test
    void prepareShouldAttachRuntimeContextWhenPrincipalProvided() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent agent = mock(Agent.class);
        when(agent.id()).thenReturn("demo-agent");
        when(agent.name()).thenReturn("Demo Agent");
        when(agentRegistry.get("demo-agent")).thenReturn(agent);

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "demo-agent", "Demo Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "Chat Alpha", "demo-agent", null, 1L, 2L, "", "", 1, 2L, false));

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        QueryRequest request = new QueryRequest(
                "req-1",
                chatId,
                "demo-agent",
                null,
                "user",
                "hello",
                List.of(new QueryRequest.Reference("ref-1", "file", "notes.md", "text/markdown", 42L, null, null, null)),
                Map.of("ignored", "value"),
                new QueryRequest.Scene("https://example.com", "Example"),
                true
        );
        JwksJwtVerifier.JwtPrincipal principal = new JwksJwtVerifier.JwtPrincipal(
                "user-1",
                "device-1",
                "chat:write",
                Instant.parse("2026-03-20T10:15:30Z"),
                Instant.parse("2026-03-21T10:15:30Z")
        );

        AgentQueryService.QuerySession session = service.prepare(request, principal);

        RuntimeRequestContext runtimeContext = session.agentRequest().runtimeContext();
        assertThat(runtimeContext).isNotNull();
        assertThat(runtimeContext.agentKey()).isEqualTo("demo-agent");
        assertThat(runtimeContext.chatName()).isEqualTo("Chat Alpha");
        assertThat(runtimeContext.scene()).isEqualTo(request.scene());
        assertThat(runtimeContext.references()).hasSize(1);
        assertThat(runtimeContext.authPrincipal()).isEqualTo(principal);
        assertThat(runtimeContext.workspacePaths()).isNotNull();
        assertThat(runtimeContext.workspacePaths().chatAttachmentsDir()).contains(chatId);
        assertThat(runtimeContext.sandboxContext()).isNull();
        assertThat(runtimeContext.agentDigests()).isEmpty();
    }

    @Test
    void prepareShouldAttachSandboxAndAllAgentsRuntimeContext() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent agent = mock(Agent.class);
        Agent otherAgent = mock(Agent.class);
        AgentDefinition definition = agentDefinition(
                "demo-agent",
                List.of(RuntimeContextTags.SANDBOX, RuntimeContextTags.ALL_AGENTS),
                List.of("sandbox_bash"),
                List.of("docx"),
                "daily-office"
        );
        AgentDefinition otherDefinition = agentDefinition(
                "writer",
                List.of(),
                List.of(),
                List.of("docx"),
                null
        );
        when(agent.id()).thenReturn("demo-agent");
        when(agent.name()).thenReturn("Demo Agent");
        when(agent.role()).thenReturn("Demo Role");
        when(agent.description()).thenReturn("Demo Description");
        when(agent.mode()).thenReturn(AgentRuntimeMode.ONESHOT);
        when(agent.tools()).thenReturn(List.of("sandbox_bash"));
        when(agent.skills()).thenReturn(List.of("docx"));
        when(agent.definition()).thenReturn(Optional.of(definition));
        when(otherAgent.id()).thenReturn("writer");
        when(otherAgent.name()).thenReturn("Writer");
        when(otherAgent.role()).thenReturn("Writer Role");
        when(otherAgent.description()).thenReturn("Writer Description");
        when(otherAgent.mode()).thenReturn(AgentRuntimeMode.ONESHOT);
        when(otherAgent.tools()).thenReturn(List.of());
        when(otherAgent.skills()).thenReturn(List.of("docx"));
        when(otherAgent.definition()).thenReturn(Optional.of(otherDefinition));
        when(agentRegistry.get("demo-agent")).thenReturn(agent);
        when(agentRegistry.list()).thenReturn(List.of(otherAgent, agent));

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "demo-agent", "Demo Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "Chat Alpha", "demo-agent", null, 1L, 2L, "", "", 1, 2L, false));

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
        properties.setDefaultEnvironmentId("shell");

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                containerHubClient,
                properties
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "demo-agent", null, "user", "hello", null, null, null, true);
        AgentQueryService.QuerySession session = service.prepare(request);

        RuntimeRequestContext runtimeContext = session.agentRequest().runtimeContext();
        assertThat(runtimeContext.sandboxContext()).isNotNull();
        assertThat(runtimeContext.sandboxContext().environmentId()).isEqualTo("daily-office");
        assertThat(runtimeContext.sandboxContext().environmentPrompt()).contains("daily-office");
        assertThat(runtimeContext.agentDigests()).hasSize(2);
        assertThat(runtimeContext.agentDigests()).extracting(RuntimeRequestContext.AgentDigest::key)
                .containsExactly("demo-agent", "writer");
    }

    @Test
    void prepareShouldFailWhenSandboxPromptMissingAndLogReason(CapturedOutput output) {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent agent = mock(Agent.class);
        AgentDefinition definition = agentDefinition(
                "demo-agent",
                List.of(RuntimeContextTags.SANDBOX),
                List.of("sandbox_bash"),
                List.of(),
                "daily-office"
        );
        when(agent.id()).thenReturn("demo-agent");
        when(agent.name()).thenReturn("Demo Agent");
        when(agent.definition()).thenReturn(Optional.of(definition));
        when(agentRegistry.get("demo-agent")).thenReturn(agent);

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "demo-agent", "Demo Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "Chat Alpha", "demo-agent", null, 1L, 2L, "", "", 1, 2L, false));

        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                ContainerHubClient.EnvironmentAgentPromptResult.failure("daily-office", "hub unavailable")
        );

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                containerHubClient,
                new ContainerHubToolProperties()
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "demo-agent", null, "user", "hello", null, null, null, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox context failed to load environment prompt");
        assertThat(output.getAll()).contains("Sandbox agent prompt fetch failed");
        assertThat(output.getAll()).contains("environmentId=daily-office");
    }

    @Test
    void prepareShouldMergeChatDirectoryAssetsIntoReferences() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent agent = mock(Agent.class);
        when(agent.id()).thenReturn("demo-agent");
        when(agent.name()).thenReturn("Demo Agent");
        when(agentRegistry.get("demo-agent")).thenReturn(agent);

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        String chatId = UUID.randomUUID().toString();
        when(chatRecordStore.findBoundAgentKey(chatId)).thenReturn(Optional.of("demo-agent"));
        when(chatRecordStore.findBoundTeamId(chatId)).thenReturn(Optional.empty());
        when(chatRecordStore.ensureChat(chatId, "demo-agent", "Demo Agent", null, "hello"))
                .thenReturn(new ChatRecordStore.ChatSummary(chatId, "hello", "demo-agent", null, 1L, 2L, "", "", 1, 2L, false));

        ChatAssetCatalogService chatAssetCatalogService = mock(ChatAssetCatalogService.class);
        QueryRequest.Reference localAsset = new QueryRequest.Reference(
                "asset_local",
                "image",
                "cover.png",
                "image/png",
                1L,
                "/data/" + chatId + "/cover.png",
                null,
                Map.of("relativePath", "cover.png")
        );
        when(chatAssetCatalogService.mergeWithChatAssets(chatId, List.of()))
                .thenReturn(List.of(localAsset));

        AgentQueryService service = new AgentQueryService(
                agentRegistry,
                mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class),
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new com.linlay.agentplatform.config.LoggingAgentProperties(),
                chatAssetCatalogService,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "demo-agent", null, "user", "hello", List.of(), null, null, true);
        AgentQueryService.QuerySession session = service.prepare(request);

        assertThat(session.request().references()).hasSize(1);
        assertThat(session.request().references().getFirst()).isEqualTo(localAsset);
        assertThat((List<?>) session.agentRequest().query().get("references")).hasSize(1);
    }

    @Test
    void streamShouldReportRunCompletionToChatStore() {
        Agent agent = mock(Agent.class);
        when(agent.stream(any(AgentRequest.class))).thenReturn(Flux.<AgentDelta>empty());

        com.linlay.agentplatform.stream.service.StreamSseStreamer streamer = mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class);
        when(streamer.stream(any(StreamRequest.class), any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"type\":\"content.delta\",\"delta\":\"hello\"}").build(),
                ServerSentEvent.builder("{\"type\":\"run.complete\",\"runId\":\"run-1\",\"timestamp\":100}").build()
        ));

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        AgentQueryService service = new AgentQueryService(
                mock(AgentRegistry.class),
                streamer,
                objectMapper,
                chatRecordStore,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                agent,
                new StreamRequest.Query("req-1", UUID.randomUUID().toString(), "user", "fallback", "demo", null, null, null, null, true, "chat", "run-1"),
                new AgentRequest("fallback", UUID.randomUUID().toString(), "req-1", "run-1", Map.of())
        );

        List<ServerSentEvent<String>> events = service.stream(session).collectList().block();
        assertThat(events).hasSize(2);
        verify(chatRecordStore).onRunCompleted(any(ChatRecordStore.RunCompletion.class));
    }

    @Test
    void streamShouldHideClientInvisibleToolEvents() {
        Agent agent = mock(Agent.class);
        when(agent.stream(any(AgentRequest.class))).thenReturn(Flux.<AgentDelta>empty());

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("_plan_add_tasks_")).thenReturn(Optional.of(hiddenTool("_plan_add_tasks_")));

        com.linlay.agentplatform.stream.service.StreamSseStreamer streamer = mock(com.linlay.agentplatform.stream.service.StreamSseStreamer.class);
        when(streamer.stream(any(StreamRequest.class), any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"type\":\"tool.start\",\"toolName\":\"_plan_add_tasks_\",\"toolId\":\"call_hidden_1\",\"runId\":\"run-1\"}").build(),
                ServerSentEvent.builder("{\"type\":\"tool.args\",\"toolId\":\"call_hidden_1\",\"delta\":\"{}\"}").build(),
                ServerSentEvent.builder("{\"type\":\"tool.end\",\"toolId\":\"call_hidden_1\"}").build(),
                ServerSentEvent.builder("{\"type\":\"tool.result\",\"toolId\":\"call_hidden_1\",\"result\":\"ok\"}").build(),
                ServerSentEvent.builder("{\"type\":\"content.delta\",\"delta\":\"final\"}").build(),
                ServerSentEvent.builder("{\"type\":\"run.complete\",\"runId\":\"run-1\",\"timestamp\":200}").build()
        ));

        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        AgentQueryService service = new AgentQueryService(
                mock(AgentRegistry.class),
                streamer,
                objectMapper,
                chatRecordStore,
                toolRegistry,
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        String chatId = UUID.randomUUID().toString();
        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                agent,
                new StreamRequest.Query("req-1", chatId, "user", "fallback", "demo", null, null, null, null, true, "chat", "run-1"),
                new AgentRequest("fallback", chatId, "req-1", "run-1", Map.of())
        );

        List<ServerSentEvent<String>> events = service.stream(session).collectList().block();
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(ServerSentEvent::data))
                .allMatch(item -> item.contains("\"content.delta\"") || item.contains("\"run.complete\""));
        verify(chatRecordStore, times(2)).appendEvent(any(String.class), any(String.class));
    }

    @Test
    void streamShouldNotEmitRequestSteerUntilNextModelTurnConsumesIt() {
        Agent agent = mock(Agent.class);
        Sinks.Many<AgentDelta> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(agent.stream(any(AgentRequest.class))).thenReturn(upstream.asFlux());

        ActiveRunService activeRunService = new ActiveRunService(mock(FrontendSubmitCoordinator.class));
        AgentQueryService service = new AgentQueryService(
                mock(AgentRegistry.class),
                new com.linlay.agentplatform.stream.service.StreamSseStreamer(
                        new com.linlay.agentplatform.stream.service.StreamEventAssembler(),
                        objectMapper
                ),
                objectMapper,
                mock(ChatRecordStore.class),
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new com.linlay.agentplatform.config.LoggingAgentProperties(),
                null,
                activeRunService,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );

        String chatId = UUID.randomUUID().toString();
        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                agent,
                new StreamRequest.Query("req-1", chatId, "user", "fallback", "demo", null, null, null, null, true, "chat", "run-1"),
                new AgentRequest("fallback", chatId, "req-1", "run-1", Map.of())
        );

        CompletableFuture<List<ServerSentEvent<String>>> collectedFuture = CompletableFuture.supplyAsync(() ->
                service.stream(session).collectList().block(Duration.ofSeconds(5))
        );

        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline && activeRunService.findControl("run-1").isEmpty()) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        ActiveRunService.SteerAck ack = activeRunService.steer(new com.linlay.agentplatform.model.api.SteerRequest(
                "req-steer",
                chatId,
                "run-1",
                "steer-1",
                "demo",
                null,
                "please continue carefully",
                false
        ));
        assertThat(ack.accepted()).isTrue();

        upstream.tryEmitNext(AgentDelta.content("done"));
        upstream.tryEmitNext(AgentDelta.finish("stop"));
        upstream.tryEmitComplete();

        List<ServerSentEvent<String>> events = collectedFuture.join();
        List<String> payloads = new ArrayList<>();
        for (ServerSentEvent<String> event : events) {
            payloads.add(event.data());
        }

        assertThat(payloads).noneMatch(item -> item != null && item.contains("\"type\":\"request.steer\""));
        assertThat(payloads).anyMatch(item -> item != null && item.contains("\"type\":\"content.delta\""));
        assertThat(payloads).anyMatch(item -> item != null && item.contains("\"type\":\"run.complete\""));
    }

    private ToolDescriptor frontendTool() {
        return new ToolDescriptor(
                "confirm_dialog",
                "确认框",
                "confirm",
                "",
                Map.of("type", "object"),
                false,
                true,
                false,
                "html",
                "local",
                null,
                "confirm_dialog",
                "/tmp/confirm_dialog.frontend"
        );
    }

    private ToolDescriptor hiddenTool(String toolName) {
        return new ToolDescriptor(
                toolName,
                null,
                "hidden",
                "",
                Map.of("type", "object"),
                false,
                false,
                false,
                null,
                "local",
                null,
                null,
                "/tmp/hidden.backend"
        );
    }

    private AgentQueryService newService() {
        return new AgentQueryService(
                null,
                null,
                objectMapper,
                null,
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                mock(TeamRegistryService.class),
                new LoggingAgentProperties(),
                null,
                null,
                null,
                new RuntimeContextPromptService(),
                null,
                new ContainerHubToolProperties()
        );
    }

    private AgentDefinition agentDefinition(
            String key,
            List<String> contextTags,
            List<String> tools,
            List<String> skills,
            String environmentId
    ) {
        return new AgentDefinition(
                key,
                key,
                null,
                key + " description",
                key + " role",
                "model-key",
                "provider",
                "model-id",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.NONE, Budget.DEFAULT),
                new OneshotMode(new StageSettings("prompt", null, null, tools, false, ComputePolicy.MEDIUM), null, null),
                tools,
                skills,
                List.of(),
                new AgentDefinition.SandboxConfig(environmentId),
                List.of(),
                null,
                null,
                contextTags,
                List.of(),
                null
        );
    }
}
