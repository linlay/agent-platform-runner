package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.FrontendToolProperties;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.model.ViewportType;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                mock(TeamRegistryService.class)
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
                mock(TeamRegistryService.class)
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
                mock(TeamRegistryService.class)
        );

        QueryRequest request = new QueryRequest("req-1", chatId, "request-agent", null, "user", "hello", null, null, null, true);
        AgentQueryService.QuerySession session = service.prepare(request);

        assertThat(session.agent().id()).isEqualTo("bound-agent");
        assertThat(session.request().agentKey()).isEqualTo("bound-agent");
        assertThat(session.agentRequest().query()).containsEntry("agentKey", "bound-agent");
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
                chatAssetCatalogService
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
                mock(TeamRegistryService.class)
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
                mock(TeamRegistryService.class)
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
                mock(TeamRegistryService.class)
        );
    }
}
