package com.linlay.springaiagw.agent.runtime.policy;

public record RunSpec(
        ControlStrategy control,
        OutputPolicy output,
        ToolPolicy toolPolicy,
        Budget budget
) {
    public RunSpec {
        if (control == null) {
            control = ControlStrategy.ONESHOT;
        }
        if (output == null) {
            output = OutputPolicy.PLAIN;
        }
        if (toolPolicy == null) {
            toolPolicy = ToolPolicy.DISALLOW;
        }
        if (budget == null) {
            budget = Budget.DEFAULT;
        }
    }
}
