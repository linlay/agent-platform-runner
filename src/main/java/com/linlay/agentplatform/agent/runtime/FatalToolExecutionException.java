package com.linlay.agentplatform.agent.runtime;

public class FatalToolExecutionException extends RuntimeException {

    private final String code;

    public FatalToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
