package com.linlay.agentplatform.engine.exception;

public class RunInterruptedException extends RuntimeException {

    public RunInterruptedException() {
        super("Run interrupted");
    }

    public RunInterruptedException(String message) {
        super(message);
    }
}
