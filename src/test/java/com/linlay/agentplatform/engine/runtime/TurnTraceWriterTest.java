package com.linlay.agentplatform.engine.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.ChatStorageProperties;
import com.linlay.agentplatform.chat.storage.ChatStorageStore;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TurnTraceWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistConsumedSteerAsUserMessageInStepHistory() {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);
        ChatStorageStore store = new ChatStorageStore(new ObjectMapper(), properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174188";
        String runId = "run_trace_1";
        AgentRequest request = new AgentRequest(
                "initial request",
                chatId,
                "req_trace_1",
                runId,
                Map.of(
                        "requestId", "req_trace_1",
                        "chatId", chatId,
                        "role", "user",
                        "message", "initial request",
                        "agentKey", "demoModeReact"
                )
        );

        TurnTraceWriter writer = new TurnTraceWriter(store, () -> null, request, runId, null);
        writer.capture(AgentDelta.stageMarker("react-step-1"));
        writer.capture(AgentDelta.userMessage("please verify before finishing"));
        writer.capture(AgentDelta.content("done"));
        writer.finalFlush();

        List<ChatMessage> history = store.loadHistoryMessages(chatId);
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(new ChatMessage.UserMsg("initial request"));
        assertThat(history.get(1)).isEqualTo(new ChatMessage.UserMsg("please verify before finishing"));
        assertThat(history.get(2)).isEqualTo(new ChatMessage.AssistantMsg("done"));
    }
}
