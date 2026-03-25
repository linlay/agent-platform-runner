package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.RuntimeContextPromptService;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitCoordinator;
import com.linlay.agentplatform.agent.runtime.SandboxContextResolver;
import com.linlay.agentplatform.config.properties.FrontendToolProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.service.chat.ChatAssetCatalogService;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import com.linlay.agentplatform.service.viewport.ViewportRegistryService;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.service.SseEventNormalizer;
import com.linlay.agentplatform.stream.service.StreamSseStreamer;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.codec.ServerSentEvent;
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

        AgentQueryService service = newService(
                agentRegistry,
                mock(StreamSseStreamer.class),
                chatRecordStore,
                mock(ToolRegistry.class),
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

        AgentQueryService service = newService(
                agentRegistry,
                mock(StreamSseStreamer.class),
                chatRecordStore,
                mock(ToolRegistry.class),
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

        AgentQueryService service = newService(
                agentRegistry,
                mock(StreamSseStreamer.class),
                chatRecordStore,
                mock(ToolRegistry.class),
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
        assertThat(runtimeContext.localPaths()).isNotNull();
        assertThat(runtimeContext.localPaths().chatAttachmentsDir()).contains(chatId);
        assertThat(runtimeContext.sandboxPaths()).isNotNull();
        assertThat(runtimeContext.sandboxPaths().workspaceDir()).isEqualTo("/workspace");
        assertThat(runtimeContext.sandboxContext()).isNull();
        assertThat(runtimeContext.agentDigests()).isEmpty();
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
                "/api/resource?file=" + chatId + "%2Fcover.png",
                null,
                Map.of("relativePath", "cover.png")
        );
        when(chatAssetCatalogService.mergeWithChatAssets(chatId, List.of()))
                .thenReturn(List.of(localAsset));

        AgentQueryService service = newService(
                agentRegistry,
                mock(StreamSseStreamer.class),
                chatRecordStore,
                mock(ToolRegistry.class),
                new LoggingAgentProperties(),
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
        AgentQueryService service = newService(
                mock(AgentRegistry.class),
                streamer,
                chatRecordStore,
                mock(ToolRegistry.class),
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
        AgentQueryService service = newService(
                mock(AgentRegistry.class),
                streamer,
                chatRecordStore,
                toolRegistry,
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
        AgentQueryService service = newService(
                mock(AgentRegistry.class),
                new StreamSseStreamer(
                        new com.linlay.agentplatform.stream.service.StreamEventAssembler(),
                        objectMapper
                ),
                mock(ChatRecordStore.class),
                mock(ToolRegistry.class),
                new LoggingAgentProperties(),
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

    private AgentQueryService newService(
            AgentRegistry agentRegistry,
            StreamSseStreamer streamSseStreamer,
            ChatRecordStore chatRecordStore,
            ToolRegistry toolRegistry,
            LoggingAgentProperties loggingAgentProperties,
            ChatAssetCatalogService chatAssetCatalogService,
            ActiveRunService activeRunService,
            com.linlay.agentplatform.stream.service.RenderQueue renderQueue,
            RuntimeContextPromptService runtimeContextPromptService,
            ContainerHubClient containerHubClient,
            ContainerHubToolProperties containerHubToolProperties
    ) {
        ToolRegistry effectiveToolRegistry = toolRegistry == null ? mock(ToolRegistry.class) : toolRegistry;
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        SandboxContextResolver sandboxContextResolver = new SandboxContextResolver(
                containerHubClient,
                containerHubToolProperties == null ? new ContainerHubToolProperties() : containerHubToolProperties
        );
        return new AgentQueryService(
                agentRegistry,
                streamSseStreamer,
                objectMapper,
                chatRecordStore,
                effectiveToolRegistry,
                mock(TeamRegistryService.class),
                loggingAgentProperties == null ? new LoggingAgentProperties() : loggingAgentProperties,
                chatAssetCatalogService,
                activeRunService,
                renderQueue,
                runtimeContextPromptService == null ? new RuntimeContextPromptService() : runtimeContextPromptService,
                new SseEventNormalizer(objectMapper, effectiveToolRegistry, viewportRegistryService, frontendToolProperties),
                sandboxContextResolver,
                containerHubToolProperties
        );
    }
}
