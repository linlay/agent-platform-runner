package com.linlay.agentplatform.agent.runtime.exception;

public class BudgetExceededException extends RuntimeException {

    public enum Kind {
        MODEL_CALLS,
        TOOL_CALLS,
        RUN_TIMEOUT
    }

    private final Kind kind;

    public BudgetExceededException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
