package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.model.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionContextBudgetTest {

    @Test
    void shouldFailWhenRunTimeoutExceeded() throws InterruptedException {
        Budget budget = new Budget(
                1L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(10, 10_000L, 0)
        );
        ExecutionContext context = contextWithBudget(budget);

        Thread.sleep(10);

        assertThatThrownBy(context::checkBudget)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("runTimeoutMs=1");
    }

    @Test
    void shouldFailWhenModelMaxCallsExceeded() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(1, 10_000L, 0),
                new Budget.Scope(10, 10_000L, 0)
        );
        ExecutionContext context = contextWithBudget(budget);
        context.incrementModelCalls();

        assertThatThrownBy(context::incrementModelCalls)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model.maxCalls=1");
    }

    @Test
    void shouldFailWhenToolMaxCallsExceeded() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(1, 10_000L, 0)
        );
        ExecutionContext context = contextWithBudget(budget);
        context.incrementToolCalls(1);

        assertThatThrownBy(() -> context.incrementToolCalls(1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tool.maxCalls=1");
    }

    private ExecutionContext contextWithBudget(Budget budget) {
        AgentDefinition definition = new AgentDefinition(
                "budget_test",
                "budget_test",
                null,
                "budget test",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, budget),
                new OneshotMode(
                        new StageSettings("sys", null, null, List.of(), false, ComputePolicy.MEDIUM),
                        null,
                        null
                ),
                List.of(),
                List.of()
        );
        AgentRequest request = new AgentRequest("hello", "chat_1", "req_1", "run_1");
        return new ExecutionContext(definition, request, List.of());
    }
}
