package com.linlay.agentplatform.agent.runtime.exception;

public class FrontendSubmitTimeoutException extends RuntimeException {

    private final String toolId;
    private final String toolName;
    private final String resultText;
    private final long timeoutMs;
    private final long elapsedMs;

    public FrontendSubmitTimeoutException(String message) {
        this(message, null, null, null, 0L, 0L);
    }

    public FrontendSubmitTimeoutException(
            String message,
            String toolId,
            String toolName,
            String resultText,
            long timeoutMs,
            long elapsedMs
    ) {
        super(message);
        this.toolId = toolId;
        this.toolName = toolName;
        this.resultText = resultText;
        this.timeoutMs = timeoutMs;
        this.elapsedMs = elapsedMs;
    }

    public String toolId() {
        return toolId;
    }

    public String toolName() {
        return toolName;
    }

    public String resultText() {
        return resultText;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public long elapsedMs() {
        return elapsedMs;
    }
}
