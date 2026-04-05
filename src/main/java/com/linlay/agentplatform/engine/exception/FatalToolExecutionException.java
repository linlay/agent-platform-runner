package com.linlay.agentplatform.engine.exception;

public class FatalToolExecutionException extends RuntimeException {

    private final String code;
    private final String toolId;
    private final String toolName;

    public FatalToolExecutionException(String code, String message) {
        this(code, message, null, null);
    }

    public FatalToolExecutionException(String code, String message, String toolId, String toolName) {
        super(message);
        this.code = code;
        this.toolId = toolId;
        this.toolName = toolName;
    }

    public String code() {
        return code;
    }

    public String toolId() {
        return toolId;
    }

    public String toolName() {
        return toolName;
    }
}
