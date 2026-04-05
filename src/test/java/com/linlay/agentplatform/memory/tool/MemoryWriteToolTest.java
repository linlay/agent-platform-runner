package com.linlay.agentplatform.memory.tool;

import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.mode.OneshotMode;
import com.linlay.agentplatform.engine.mode.StageSettings;
import com.linlay.agentplatform.engine.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.engine.policy.Budget;
import com.linlay.agentplatform.engine.policy.ComputePolicy;
import com.linlay.agentplatform.engine.policy.RunSpec;
import com.linlay.agentplatform.engine.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.store.MemoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryWriteToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteMemoryAndAppendMarkdown() throws Exception {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        AgentMemoryProperties properties = new AgentMemoryProperties();
        MemoryWriteTool tool = new MemoryWriteTool(store, properties);

        Path agentDir = tempDir.resolve("writer");
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.definition()).thenReturn(definition("writer", agentDir));

        MemoryRecord record = new MemoryRecord("mem_1234abcd", "writer", "agent:writer", "Keep answers concise", "tool-write", "preference", 8, List.of("style"), false, null, 1L, 1L, 0, null);
        when(store.write(org.mockito.ArgumentMatchers.any(AgentMemoryStore.WriteRequest.class))).thenReturn(record);

        String result = tool.invoke(Map.of(
                "content", "Keep answers concise",
                "category", "preference",
                "importance", 8,
                "tags", List.of("style")
        ), context).toString();

        assertThat(result).contains("\"status\":\"stored\"").contains("\"sourceType\":\"tool-write\"").contains("\"subjectKey\":\"agent:writer\"");
    }

    @Test
    void shouldRejectMissingContent() {
        MemoryWriteTool tool = new MemoryWriteTool(mock(AgentMemoryStore.class), new AgentMemoryProperties());
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.definition()).thenReturn(definition("writer", tempDir.resolve("writer")));

        assertThatThrownBy(() -> tool.invoke(Map.of(), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing argument: content");
    }

    private AgentDefinition definition(String agentKey, Path agentDir) {
        return new AgentDefinition(
                agentKey,
                agentKey,
                null,
                "demo",
                "role",
                null,
                "provider",
                "model",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.NONE, Budget.DEFAULT),
                new OneshotMode(new StageSettings("prompt", null, null, List.of(), false, ComputePolicy.MEDIUM, "prompt"), null, null),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                agentDir
        );
    }
}
