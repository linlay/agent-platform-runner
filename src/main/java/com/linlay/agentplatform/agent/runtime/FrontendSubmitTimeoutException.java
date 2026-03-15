package com.linlay.agentplatform.agent.runtime;

public class FrontendSubmitTimeoutException extends RuntimeException {

    private final String toolId;
    private final String resultText;

    public FrontendSubmitTimeoutException(String message) {
        this(message, null, null);
    }

    public FrontendSubmitTimeoutException(String message, String toolId, String resultText) {
        super(message);
        this.toolId = toolId;
        this.resultText = resultText;
    }

    public String toolId() {
        return toolId;
    }

    public String resultText() {
        return resultText;
    }
}
