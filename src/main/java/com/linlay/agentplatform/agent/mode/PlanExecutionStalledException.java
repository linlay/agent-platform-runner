package com.linlay.agentplatform.agent.mode;

final class PlanExecutionStalledException extends RuntimeException {

    PlanExecutionStalledException(String message) {
        super(message);
    }
}
