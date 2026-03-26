package com.linlay.agentplatform.tool;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentMemoryService;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.memory.AgentMemoryStore;
import com.linlay.agentplatform.service.memory.MemoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        properties.setDualWriteMarkdown(true);
        AgentMemoryService agentMemoryService = new AgentMemoryService();
        MemoryWriteTool tool = new MemoryWriteTool(store, properties, agentMemoryService);

        Path agentDir = tempDir.resolve("writer");
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.definition()).thenReturn(definition("writer", agentDir));

        MemoryRecord record = new MemoryRecord("mem_1234abcd", "writer", "Keep answers concise", "preference", 8, List.of("style"), false, 1L, 1L, 0, null);
        when(store.write("writer", agentDir, "Keep answers concise", "preference", 8, List.of("style"))).thenReturn(record);

        String result = tool.invoke(Map.of(
                "content", "Keep answers concise",
                "category", "preference",
                "importance", 8,
                "tags", List.of("style")
        ), context).toString();

        assertThat(result).contains("\"status\":\"stored\"");
        assertThat(Files.readString(agentDir.resolve("memory/memory.md"))).contains("[mem_1234abcd]").contains("Keep answers concise");
    }

    @Test
    void shouldRejectMissingContent() {
        MemoryWriteTool tool = new MemoryWriteTool(mock(AgentMemoryStore.class), new AgentMemoryProperties(), new AgentMemoryService());
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
