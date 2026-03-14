package com.linlay.agentplatform.agent.runtime;

public class RunInterruptedException extends RuntimeException {

    public RunInterruptedException() {
        super("Run interrupted");
    }

    public RunInterruptedException(String message) {
        super(message);
    }
}
