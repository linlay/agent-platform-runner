package com.linlay.agentplatform.tool;

import com.linlay.agentplatform.agent.AgentDefinition;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryReadToolTest {

    @Test
    void shouldReadById() {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        MemoryReadTool tool = new MemoryReadTool(store, new AgentMemoryProperties());
        ExecutionContext context = context("reader", Path.of("/tmp/reader"));

        when(store.read("reader", Path.of("/tmp/reader"), "mem_1")).thenReturn(Optional.of(
                new MemoryRecord("mem_1", "reader", "agent:reader", "memory", "tool-write", "general", 5, List.of(), false, null, 1L, 1L, 1, 2L)
        ));

        String result = tool.invoke(Map.of("id", "mem_1"), context).toString();

        assertThat(result).contains("\"found\":true").contains("\"id\":\"mem_1\"");
    }

    @Test
    void shouldListByCategoryAndSort() {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        MemoryReadTool tool = new MemoryReadTool(store, new AgentMemoryProperties());
        ExecutionContext context = context("reader", Path.of("/tmp/reader"));
        when(store.list("reader", Path.of("/tmp/reader"), "fact", 3, "importance")).thenReturn(List.of(
                new MemoryRecord("mem_1", "reader", "agent:reader", "memory", "tool-write", "fact", 9, List.of("a"), false, null, 1L, 1L, 0, null)
        ));

        String result = tool.invoke(Map.of("category", "fact", "limit", 3, "sort", "importance"), context).toString();

        assertThat(result).contains("\"count\":1").contains("\"category\":\"fact\"");
    }

    private ExecutionContext context(String agentKey, Path agentDir) {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.definition()).thenReturn(new AgentDefinition(
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
        ));
        return context;
    }
}
