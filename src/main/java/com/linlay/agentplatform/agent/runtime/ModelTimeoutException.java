package com.linlay.agentplatform.agent.runtime;

public class ModelTimeoutException extends RuntimeException {

    private final String stage;
    private final long timeoutMs;
    private final long elapsedMs;

    public ModelTimeoutException(String message, String stage, long timeoutMs, long elapsedMs, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.timeoutMs = timeoutMs;
        this.elapsedMs = elapsedMs;
    }

    public String stage() {
        return stage;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public long elapsedMs() {
        return elapsedMs;
    }
}
