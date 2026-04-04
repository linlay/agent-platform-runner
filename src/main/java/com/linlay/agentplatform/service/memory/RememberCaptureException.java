package com.linlay.agentplatform.service.memory;

public class RememberCaptureException extends RuntimeException {

    public RememberCaptureException(String message) {
        super(message);
    }

    public RememberCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
