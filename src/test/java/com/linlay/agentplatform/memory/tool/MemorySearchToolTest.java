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
import com.linlay.agentplatform.memory.store.ScoredMemory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySearchToolTest {

    @Test
    void shouldRenderScoredResults() {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        MemorySearchTool tool = new MemorySearchTool(store, new AgentMemoryProperties());
        ExecutionContext context = context("searcher", Path.of("/tmp/searcher"));

        when(store.search("searcher", Path.of("/tmp/searcher"), "alpha", "fact", 2)).thenReturn(List.of(
                new ScoredMemory(
                        new MemoryRecord("mem_1", "searcher", "agent:searcher", "alpha memory", "tool-write", "fact", 8, List.of("alpha"), true, "embed-demo", 1L, 1L, 2, 3L),
                        0.93d,
                        "hybrid"
                )
        ));

        String result = tool.invoke(Map.of("query", "alpha", "category", "fact", "limit", 2), context).toString();

        assertThat(result).contains("\"count\":1").contains("\"matchType\":\"hybrid\"").contains("\"score\":0.93");
    }

    @Test
    void shouldRejectMissingQuery() {
        MemorySearchTool tool = new MemorySearchTool(mock(AgentMemoryStore.class), new AgentMemoryProperties());

        assertThatThrownBy(() -> tool.invoke(Map.of(), context("searcher", Path.of("/tmp/searcher"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing argument: query");
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
