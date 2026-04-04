package com.linlay.agentplatform.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalMemoryRequestServiceTest {

    @Test
    void captureRememberShouldFailWhenRememberModelKeyIsMissing() {
        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        when(chatRecordStore.loadChat("chat-1", true)).thenReturn(chatDetail("chat-1"));
        when(chatRecordStore.findBoundAgentKey("chat-1")).thenReturn(Optional.of("agent-a"));

        AgentMemoryProperties properties = new AgentMemoryProperties();
        GlobalMemoryRequestService service = new GlobalMemoryRequestService(
                chatRecordStore,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                properties
        );

        assertThatThrownBy(() -> service.captureRemember(new RememberRequest("remember_req_1", "chat-1")).block())
                .isInstanceOf(RememberCaptureException.class)
                .hasMessageContaining("modelKey is not configured");
    }

    @Test
    void captureRememberShouldFailWhenRememberModelKeyDoesNotExist() {
        ChatRecordStore chatRecordStore = mock(ChatRecordStore.class);
        ModelRegistryService modelRegistryService = mock(ModelRegistryService.class);
        when(chatRecordStore.loadChat("chat-1", true)).thenReturn(chatDetail("chat-1"));
        when(chatRecordStore.findBoundAgentKey("chat-1")).thenReturn(Optional.of("agent-a"));
        when(modelRegistryService.find("missing-model")).thenReturn(Optional.empty());

        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.getRemember().setModelKey("missing-model");
        GlobalMemoryRequestService service = new GlobalMemoryRequestService(
                chatRecordStore,
                new ObjectMapper(),
                null,
                null,
                null,
                modelRegistryService,
                properties
        );

        assertThatThrownBy(() -> service.captureRemember(new RememberRequest("remember_req_2", "chat-1")).block())
                .isInstanceOf(RememberCaptureException.class)
                .hasMessageContaining("modelKey not found");
    }

    private ChatDetailResponse chatDetail(String chatId) {
        return new ChatDetailResponse(
                chatId,
                "demo",
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of()
        );
    }
}
