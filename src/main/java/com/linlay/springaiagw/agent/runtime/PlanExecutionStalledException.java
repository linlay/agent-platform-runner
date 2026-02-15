package com.linlay.springaiagw.agent.runtime;

public class PlanExecutionStalledException extends RuntimeException {

    public PlanExecutionStalledException(String message) {
        super(message);
    }
}
