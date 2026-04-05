package com.linlay.agentplatform.engine.mode;

final class PlanExecutionStalledException extends RuntimeException {

    PlanExecutionStalledException(String message) {
        super(message);
    }
}
