package com.linlay.agentplatform.memory.remember;

public class RememberCaptureException extends RuntimeException {

    public RememberCaptureException(String message) {
        super(message);
    }

    public RememberCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
