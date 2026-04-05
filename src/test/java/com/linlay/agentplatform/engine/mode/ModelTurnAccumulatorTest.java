package com.linlay.agentplatform.engine.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelTurnAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldEmitReasoningBeforeToolCallsWhenSameDeltaContainsBothAndSuppressLaterReasoning() {
        ModelTurnAccumulator accumulator = new ModelTurnAccumulator(objectMapper);
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.activeTaskId()).thenReturn("task_1");
        when(context.isInterrupted()).thenReturn(false);
        when(context.request()).thenReturn(new AgentRequest("test", "chat_1", "req_1", "run_1"));

        AtomicReference<OrchestratorServices.ModelTurn> turnRef = new AtomicReference<>();
        List<AgentDelta> emitted = Flux.<AgentDelta>create(sink -> {
                    turnRef.set(accumulator.accumulate(
                            List.of(
                                    new LlmDelta(
                                            "推理摘要",
                                            null,
                                            List.of(new ToolCallDelta("call_1", "function", "bash", "{\"command\":\"ls\"}")),
                                            null
                                    ),
                                    new LlmDelta("工具后推理不应外发", null, null, "stop")
                            ),
                            context,
                            "agent-react-step-1",
                            true,
                            true,
                            true,
                            sink
                    ));
                    sink.complete();
                })
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(emitted).isNotNull();
        assertThat(emitted.stream()
                .map(AgentDelta::reasoning)
                .filter(text -> text != null && !text.isBlank()))
                .containsExactly("推理摘要");
        assertThat(emitted.stream()
                .flatMap(delta -> delta.toolCalls().stream())
                .map(ToolCallDelta::id))
                .containsExactly("call_1");
        assertThat(emitted.stream()
                .flatMap(delta -> delta.toolEnds().stream()))
                .containsExactly("call_1");
        assertThat(turnRef.get()).isNotNull();
        assertThat(turnRef.get().reasoningText()).isEqualTo("推理摘要工具后推理不应外发");
    }
}
