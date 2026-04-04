package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.service.memory.RememberCaptureException;
import com.linlay.agentplatform.util.LoggingSanitizer;
import com.linlay.agentplatform.service.chat.ChatNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final LoggingAgentProperties loggingAgentProperties;

    public ApiExceptionHandler(LoggingAgentProperties loggingAgentProperties) {
        this.loggingAgentProperties = loggingAgentProperties;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalArgument(IllegalArgumentException ex) {
        logHandled(HttpStatus.BAD_REQUEST, "illegal_argument", ex);
        return json(HttpStatus.BAD_REQUEST, ApiResponse.failure(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(ChatNotFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleChatNotFound(ChatNotFoundException ex) {
        logHandled(HttpStatus.NOT_FOUND, "chat_not_found", ex);
        return json(HttpStatus.NOT_FOUND, ApiResponse.failure(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(RememberCaptureException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleRememberCapture(RememberCaptureException ex) {
        logHandled(HttpStatus.INTERNAL_SERVER_ERROR, "remember_capture_failed", ex);
        return json(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiResponse.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.getMessage())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(MethodArgumentNotValidException ex) {
        return validationFailed(ex.getBindingResult().getFieldErrors(), ex);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleWebExchangeBind(WebExchangeBindException ex) {
        return validationFailed(ex.getBindingResult().getFieldErrors(), ex);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> validationFailed(
            Iterable<FieldError> fieldErrors,
            Exception ex
    ) {
        Map<String, String> fields = new HashMap<>();
        for (FieldError fieldError : fieldErrors) {
            fields.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        Map<String, Object> data = Map.of("fields", fields);
        logHandled(HttpStatus.BAD_REQUEST, "validation_failed", ex);
        return json(HttpStatus.BAD_REQUEST, ApiResponse.failure(HttpStatus.BAD_REQUEST.value(), "Validation failed", data));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        int status = statusCode.value();
        String message = ex.getReason();
        if (message == null || message.isBlank()) {
            HttpStatus httpStatus = HttpStatus.resolve(status);
            message = httpStatus != null ? httpStatus.getReasonPhrase() : "Request failed";
        }
        logHandled(statusCode, "response_status_exception", ex);
        return json(statusCode, ApiResponse.failure(status, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUnexpected(Exception ex) {
        logHandled(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected_exception", ex);
        return json(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiResponse.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error")
        );
    }

    private <T> ResponseEntity<ApiResponse<T>> json(HttpStatusCode statusCode, ApiResponse<T> body) {
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private void logHandled(HttpStatusCode statusCode, String category, Exception ex) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getException().isEnabled()) {
            return;
        }
        int status = statusCode == null ? 500 : statusCode.value();
        String safeMessage = LoggingSanitizer.sanitizeText(ex == null ? "" : ex.getMessage());
        String exceptionClass = ex == null ? "UnknownException" : ex.getClass().getSimpleName();
        if (status >= 500) {
            log.error(
                    "api.exception status={}, category={}, exceptionClass={}, message={}",
                    status,
                    category,
                    exceptionClass,
                    safeMessage,
                    ex
            );
            return;
        }
        log.warn(
                "api.exception status={}, category={}, exceptionClass={}, message={}",
                status,
                category,
                exceptionClass,
                safeMessage
        );
    }
}
