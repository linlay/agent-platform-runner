package com.linlay.agentplatform.engine.policy;

public record RunSpec(
        ToolChoice toolChoice,
        Budget budget
) {
    public RunSpec {
        if (toolChoice == null) {
            toolChoice = ToolChoice.NONE;
        }
        if (budget == null) {
            budget = Budget.DEFAULT;
        }
    }
}
