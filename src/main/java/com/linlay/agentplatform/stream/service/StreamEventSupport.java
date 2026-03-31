package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.RunActor;
import com.linlay.agentplatform.stream.model.StreamRequest;

import java.util.LinkedHashMap;
import java.util.Map;

final class StreamEventSupport {

    private StreamEventSupport() {
    }

    static String resolveChatId(StreamRequest request) {
        if (request instanceof StreamRequest.Query q) {
            return q.chatId();
        }
        if (request instanceof StreamRequest.Submit s) {
            return s.chatId();
        }
        return ((StreamRequest.Upload) request).chatId();
    }

    static String resolveRunId(StreamRequest request) {
        if (request instanceof StreamRequest.Query query) {
            if (query.runId() != null && !query.runId().isBlank()) {
                return query.runId();
            }
            return toBase36Now();
        }
        if (request instanceof StreamRequest.Submit submit) {
            return submit.runId();
        }
        return null;
    }

    static boolean resolveEmitChatStart(Map<String, Object> params) {
        if (params == null || !params.containsKey(StreamEventAssembler.INTERNAL_PARAM_EMIT_CHAT_START)) {
            return true;
        }
        Object value = params.get(StreamEventAssembler.INTERNAL_PARAM_EMIT_CHAT_START);
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return true;
    }

    static Map<String, Object> filterVisibleQueryParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!StreamEventAssembler.INTERNAL_PARAM_EMIT_CHAT_START.equals(key)) {
                filtered.put(key, value);
            }
        });
        return filtered.isEmpty() ? null : filtered;
    }

    static String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    static String requireRunStartAgentKey(String agentKey, String chatId, String runId) {
        if (agentKey == null || agentKey.isBlank()) {
            throw new IllegalStateException("run.start requires non-blank agentKey for chatId=" + chatId + ", runId=" + runId);
        }
        return agentKey.trim();
    }

    static void putIfNonNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    static void putActor(Map<String, Object> payload, RunActor actor) {
        if (payload == null || actor == null || !actor.isPresent()) {
            return;
        }
        putIfNonNull(payload, "actorId", actor.actorId());
        putIfNonNull(payload, "actorType", actor.actorType());
        putIfNonNull(payload, "actorName", actor.actorName());
    }

    static boolean shouldEmitToolType(String toolType) {
        return toolType != null && !toolType.isBlank() && !"function".equalsIgnoreCase(toolType);
    }

    static Map<String, Object> errorPayload(Throwable ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "RUN_ERROR");
        error.put("message", resolveErrorMessage(ex));
        error.put("retriable", false);
        return error;
    }

    static String resolveErrorMessage(Throwable ex) {
        if (ex == null) {
            return "Unknown run error";
        }
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return ex.getClass().getSimpleName();
    }

    static String toBase36Now() {
        return Long.toString(System.currentTimeMillis(), 36);
    }
}
