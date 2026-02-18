package com.linlay.springaiagw.agent.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.agent.AgentDefinition;
import com.linlay.springaiagw.agent.runtime.AgentRuntimeMode;
import com.linlay.springaiagw.agent.runtime.ExecutionContext;
import com.linlay.springaiagw.agent.runtime.policy.Budget;
import com.linlay.springaiagw.agent.runtime.policy.ComputePolicy;
import com.linlay.springaiagw.agent.runtime.policy.RunSpec;
import com.linlay.springaiagw.agent.runtime.policy.ToolChoice;
import com.linlay.springaiagw.model.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorServicesTest {

    @Test
    void retryCountsShouldUseBudgetModelAndToolSettings() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 10_000L, 3),
                new Budget.Scope(10, 20_000L, 4)
        );
        ExecutionContext context = contextWithBudget(budget);
        OrchestratorServices services = new OrchestratorServices(null, null, new ObjectMapper());

        assertThat(services.modelRetryCount(context, 1)).isEqualTo(3);
        assertThat(services.toolRetryCount(context, 1)).isEqualTo(4);
    }

    @Test
    void retryCountsShouldFallbackWhenConfiguredRetryIsZero() {
        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 10_000L, 0),
                new Budget.Scope(10, 20_000L, 0)
        );
        ExecutionContext context = contextWithBudget(budget);
        OrchestratorServices services = new OrchestratorServices(null, null, new ObjectMapper());

        assertThat(services.modelRetryCount(context, 2)).isEqualTo(2);
        assertThat(services.toolRetryCount(context, 2)).isEqualTo(2);
    }

    private ExecutionContext contextWithBudget(Budget budget) {
        AgentDefinition definition = new AgentDefinition(
                "orchestrator_test",
                "orchestrator_test",
                null,
                "orchestrator test",
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
