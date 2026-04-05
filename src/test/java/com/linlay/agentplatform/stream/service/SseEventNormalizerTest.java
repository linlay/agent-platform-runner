package com.linlay.agentplatform.stream.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.FrontendToolProperties;
import com.linlay.agentplatform.model.ViewportType;
import com.linlay.agentplatform.integration.viewport.ViewportRegistryService;
import com.linlay.agentplatform.stream.autoconfigure.StreamSseProperties;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SseEventNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeEventShouldConvertHeartbeatCommentToEventWithoutData() {
        SseEventNormalizer normalizer = newNormalizer(mock(ToolRegistry.class), mock(ViewportRegistryService.class), new FrontendToolProperties());
        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .id("hb_1")
                .retry(Duration.ofSeconds(3))
                .build();

        ServerSentEvent<String> normalized = normalizer.normalizeEvent(event, new HashSet<>());

        assertThat(normalized.event()).isEqualTo("heartbeat");
        assertThat(normalized.data()).isNull();
        assertThat(normalized.comment()).isNull();
        assertThat(normalized.id()).isEqualTo("hb_1");
        assertThat(normalized.retry()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void normalizeEventShouldPreserveSeqForPlanUpdate() throws Exception {
        SseEventNormalizer normalizer = newNormalizer(mock(ToolRegistry.class), mock(ViewportRegistryService.class), new FrontendToolProperties());
        String payload = """
                {"seq":3,"type":"plan.update","planId":"plan_1","chatId":"chat_1","plan":[{"taskId":"task1","description":"d1","status":"init"}],"timestamp":1700000000000}
                """;
        ServerSentEvent<String> event = ServerSentEvent.builder(payload)
                .event("message")
                .build();

        ServerSentEvent<String> normalized = normalizer.normalizeEvent(event, new HashSet<>());
        JsonNode node = objectMapper.readTree(normalized.data());

        assertThat(node.path("seq").asLong()).isEqualTo(3L);
        assertThat(node.path("type").asText()).isEqualTo("plan.update");
        assertThat(node.path("planId").asText()).isEqualTo("plan_1");
        assertThat(node.path("chatId").asText()).isEqualTo("chat_1");
        assertThat(node.path("plan").isArray()).isTrue();
        assertThat(node.path("timestamp").asLong()).isEqualTo(1700000000000L);
    }

    @Test
    void normalizeEventShouldInjectFrontendFieldsForToolStartAndSnapshot() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(120_000);
        when(toolRegistry.descriptor("confirm_dialog")).thenReturn(Optional.of(frontendTool()));
        when(viewportRegistryService.find("confirm_dialog")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("confirm_dialog", ViewportType.HTML, "<div>ok</div>")
        ));
        SseEventNormalizer normalizer = newNormalizer(toolRegistry, viewportRegistryService, frontendToolProperties);

        ServerSentEvent<String> startEvent = ServerSentEvent.builder("""
                {"type":"tool.start","toolName":"confirm_dialog","toolId":"call_1","runId":"run_1","toolLabel":"确认框","toolDescription":"confirm"}
                """).build();
        ServerSentEvent<String> snapshotEvent = ServerSentEvent.builder("""
                {"type":"tool.snapshot","toolName":"confirm_dialog","toolId":"call_2","runId":"run_2","toolLabel":"确认框","toolDescription":"confirm"}
                """).build();

        JsonNode startPayload = objectMapper.readTree(normalizer.normalizeEvent(startEvent, new HashSet<>()).data());
        JsonNode snapshotPayload = objectMapper.readTree(normalizer.normalizeEvent(snapshotEvent, new HashSet<>()).data());

        assertFrontendPayload(startPayload, "tool.start", 120_000L);
        assertFrontendPayload(snapshotPayload, "tool.snapshot", 120_000L);
    }

    @Test
    void normalizeEventShouldHideInvisibleToolEventsAcrossLifecycle() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("_plan_add_tasks_")).thenReturn(Optional.of(hiddenTool("_plan_add_tasks_")));
        SseEventNormalizer normalizer = newNormalizer(toolRegistry, mock(ViewportRegistryService.class), new FrontendToolProperties());
        Set<String> hiddenToolIds = new HashSet<>();

        ServerSentEvent<String> startEvent = ServerSentEvent.builder("""
                {"type":"tool.start","toolName":"_plan_add_tasks_","toolId":"call_hidden_1","runId":"run_1"}
                """).build();
        ServerSentEvent<String> argsEvent = ServerSentEvent.builder("""
                {"type":"tool.args","toolId":"call_hidden_1","delta":"{}"}
                """).build();
        ServerSentEvent<String> resultEvent = ServerSentEvent.builder("""
                {"type":"tool.result","toolId":"call_hidden_1","result":"ok"}
                """).build();

        assertThat(normalizer.normalizeEvent(startEvent, hiddenToolIds)).isNull();
        assertThat(hiddenToolIds).containsExactly("call_hidden_1");
        assertThat(normalizer.normalizeEvent(argsEvent, hiddenToolIds)).isNull();
        assertThat(normalizer.normalizeEvent(resultEvent, hiddenToolIds)).isNull();
        assertThat(hiddenToolIds).isEmpty();
    }

    @Test
    void normalizeEventShouldKeepArtifactPublishAfterHiddenToolResultClosedTheLifecycle() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("_artifact_publish_")).thenReturn(Optional.of(hiddenTool("_artifact_publish_")));
        SseEventNormalizer normalizer = newNormalizer(toolRegistry, mock(ViewportRegistryService.class), new FrontendToolProperties());
        Set<String> hiddenToolIds = new HashSet<>();

        ServerSentEvent<String> startEvent = ServerSentEvent.builder("""
                {"type":"tool.start","toolName":"_artifact_publish_","toolId":"call_hidden_artifact_1","runId":"run_1"}
                """).build();
        ServerSentEvent<String> resultEvent = ServerSentEvent.builder("""
                {"type":"tool.result","toolId":"call_hidden_artifact_1","result":"{\\"ok\\":true}"}
                """).build();
        ServerSentEvent<String> artifactEvent = ServerSentEvent.builder("""
                {"type":"artifact.publish","artifactId":"asset_1","chatId":"chat_1","runId":"run_1","artifact":{"type":"file","name":"plan.md","url":"/api/resource?file=chat_1%2Fplan.md"}}
                """).build();

        assertThat(normalizer.normalizeEvent(startEvent, hiddenToolIds)).isNull();
        assertThat(normalizer.normalizeEvent(resultEvent, hiddenToolIds)).isNull();
        assertThat(hiddenToolIds).isEmpty();
        assertThat(normalizer.normalizeEvent(artifactEvent, hiddenToolIds)).isNotNull();
    }

    @Test
    void normalizeEventShouldDropToolPayloadEventsByDefault() {
        SseEventNormalizer normalizer = newNormalizer(mock(ToolRegistry.class), mock(ViewportRegistryService.class), new FrontendToolProperties());

        ServerSentEvent<String> argsEvent = ServerSentEvent.builder("""
                {"type":"tool.args","toolId":"call_1","delta":"{}"}
                """).build();
        ServerSentEvent<String> resultEvent = ServerSentEvent.builder("""
                {"type":"tool.result","toolId":"call_1","result":"ok"}
                """).build();
        ServerSentEvent<String> startEvent = ServerSentEvent.builder("""
                {"type":"tool.start","toolId":"call_1","toolName":"bash","runId":"run_1"}
                """).build();
        ServerSentEvent<String> endEvent = ServerSentEvent.builder("""
                {"type":"tool.end","toolId":"call_1"}
                """).build();

        assertThat(normalizer.normalizeEvent(argsEvent, new HashSet<>())).isNull();
        assertThat(normalizer.normalizeEvent(resultEvent, new HashSet<>())).isNull();
        assertThat(normalizer.normalizeEvent(startEvent, new HashSet<>())).isNotNull();
        assertThat(normalizer.normalizeEvent(endEvent, new HashSet<>())).isNotNull();
    }

    @Test
    void normalizeEventShouldKeepToolPayloadEventsWhenEnabled() throws Exception {
        SseEventNormalizer normalizer = newNormalizer(
                mock(ToolRegistry.class),
                mock(ViewportRegistryService.class),
                new FrontendToolProperties(),
                new StreamSseProperties(null, null, true)
        );

        ServerSentEvent<String> argsEvent = ServerSentEvent.builder("""
                {"type":"tool.args","toolId":"call_1","delta":"{}"}
                """).build();
        ServerSentEvent<String> resultEvent = ServerSentEvent.builder("""
                {"type":"tool.result","toolId":"call_1","result":"ok"}
                """).build();

        JsonNode argsPayload = objectMapper.readTree(normalizer.normalizeEvent(argsEvent, new HashSet<>()).data());
        JsonNode resultPayload = objectMapper.readTree(normalizer.normalizeEvent(resultEvent, new HashSet<>()).data());

        assertThat(argsPayload.path("type").asText()).isEqualTo("tool.args");
        assertThat(resultPayload.path("type").asText()).isEqualTo("tool.result");
    }

    private SseEventNormalizer newNormalizer(
            ToolRegistry toolRegistry,
            ViewportRegistryService viewportRegistryService,
            FrontendToolProperties frontendToolProperties
    ) {
        return newNormalizer(toolRegistry, viewportRegistryService, frontendToolProperties, new StreamSseProperties(null, null, false));
    }

    private SseEventNormalizer newNormalizer(
            ToolRegistry toolRegistry,
            ViewportRegistryService viewportRegistryService,
            FrontendToolProperties frontendToolProperties,
            StreamSseProperties streamSseProperties
    ) {
        return new SseEventNormalizer(objectMapper, toolRegistry, viewportRegistryService, frontendToolProperties, streamSseProperties);
    }

    private void assertFrontendPayload(JsonNode payload, String type, long timeoutMs) {
        assertThat(payload.path("type").asText()).isEqualTo(type);
        assertThat(payload.path("toolLabel").asText()).isEqualTo("确认框");
        assertThat(payload.path("toolDescription").asText()).isEqualTo("confirm");
        assertThat(payload.path("toolType").asText()).isEqualTo("html");
        assertThat(payload.path("viewportKey").asText()).isEqualTo("confirm_dialog");
        assertThat(payload.path("toolTimeout").asLong()).isEqualTo(timeoutMs);
        assertThat(payload.has("toolParams")).isFalse();
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
}
