package com.linlay.agentplatform.agent.runtime.exception;

public class RunInterruptedException extends RuntimeException {

    public RunInterruptedException() {
        super("Run interrupted");
    }

    public RunInterruptedException(String message) {
        super(message);
    }
}
