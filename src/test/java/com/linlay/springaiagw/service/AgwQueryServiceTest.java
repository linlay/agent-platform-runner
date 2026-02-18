package com.linlay.springaiagw.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.config.FrontendToolProperties;
import com.linlay.springaiagw.model.ViewportType;
import com.linlay.springaiagw.tool.CapabilityDescriptor;
import com.linlay.springaiagw.tool.CapabilityKind;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgwQueryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeEventShouldInjectFrontendFieldsForToolStart() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(120_000);

        when(toolRegistry.capability("confirm_dialog")).thenReturn(Optional.of(frontendCapability()));
        when(viewportRegistryService.find("confirm_dialog")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("confirm_dialog", ViewportType.HTML, "<div>ok</div>")
        ));

        AgwQueryService service = new AgwQueryService(
                null,
                null,
                objectMapper,
                null,
                toolRegistry,
                viewportRegistryService,
                frontendToolProperties
        );
        ServerSentEvent<String> event = ServerSentEvent.builder("""
                {"type":"tool.start","toolName":"confirm_dialog","toolId":"call_1","runId":"run_1"}
                """).build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);
        JsonNode payload = objectMapper.readTree(normalized.data());

        assertThat(payload.path("type").asText()).isEqualTo("tool.start");
        assertThat(payload.path("toolType").asText()).isEqualTo("html");
        assertThat(payload.path("toolKey").asText()).isEqualTo("confirm_dialog");
        assertThat(payload.path("toolTimeout").asLong()).isEqualTo(120_000L);
    }

    @Test
    void normalizeEventShouldInjectFrontendFieldsForToolSnapshot() throws Exception {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(30_000);

        when(toolRegistry.capability("confirm_dialog")).thenReturn(Optional.of(frontendCapability()));
        when(viewportRegistryService.find("confirm_dialog")).thenReturn(Optional.of(
                new ViewportRegistryService.ViewportEntry("confirm_dialog", ViewportType.QLC, Map.of("schema", Map.of()))
        ));

        AgwQueryService service = new AgwQueryService(
                null,
                null,
                objectMapper,
                null,
                toolRegistry,
                viewportRegistryService,
                frontendToolProperties
        );
        ServerSentEvent<String> event = ServerSentEvent.builder("""
                {"type":"tool.snapshot","toolName":"confirm_dialog","toolId":"call_2","runId":"run_2"}
                """).build();

        ServerSentEvent<String> normalized = ReflectionTestUtils.invokeMethod(service, "normalizeEvent", event);
        JsonNode payload = objectMapper.readTree(normalized.data());

        assertThat(payload.path("type").asText()).isEqualTo("tool.snapshot");
        assertThat(payload.path("toolType").asText()).isEqualTo("qlc");
        assertThat(payload.path("toolKey").asText()).isEqualTo("confirm_dialog");
        assertThat(payload.path("toolTimeout").asLong()).isEqualTo(30_000L);
    }

    private CapabilityDescriptor frontendCapability() {
        return new CapabilityDescriptor(
                "confirm_dialog",
                "confirm",
                "",
                Map.of("type", "object"),
                false,
                CapabilityKind.FRONTEND,
                "frontend",
                null,
                "confirm_dialog",
                "/tmp/confirm_dialog.frontend"
        );
    }
}
