package com.linlay.springaiagw.service;

import com.linlay.springaiagw.config.FrontendToolProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Component
public class FrontendSubmitCoordinator {

    private final FrontendToolProperties properties;
    private final Map<String, CompletableFuture<Object>> pendingByKey = new ConcurrentHashMap<>();

    public FrontendSubmitCoordinator(FrontendToolProperties properties) {
        this.properties = properties;
    }

    public Mono<Object> awaitSubmit(String runId, String toolId) {
        String key = key(runId, toolId);
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existed = pendingByKey.putIfAbsent(key, future);
        if (existed != null) {
            return Mono.error(new IllegalStateException("Pending frontend submit already exists: " + key));
        }

        return Mono.fromFuture(future)
                .timeout(Duration.ofMillis(Math.max(1L, properties.getSubmitTimeoutMs())))
                .onErrorMap(
                        TimeoutException.class,
                        ex -> new TimeoutException("Frontend tool submit timeout runId=" + runId + ", toolId=" + toolId)
                )
                .doFinally(signalType -> pendingByKey.remove(key));
    }

    public boolean submit(String runId, String toolId, Object payload) {
        String key = key(runId, toolId);
        CompletableFuture<Object> pending = pendingByKey.remove(key);
        if (pending == null) {
            return false;
        }
        pending.complete(extractSubmitResult(payload));
        return true;
    }

    private Object extractSubmitResult(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return Map.of();
        }
        if (!map.containsKey("params")) {
            return Map.of();
        }
        Object params = map.get("params");
        if (params == null) {
            return Map.of();
        }
        return params;
    }

    private String key(String runId, String toolId) {
        String normalizedRunId = StringUtils.hasText(runId) ? runId.trim() : "";
        String normalizedToolId = StringUtils.hasText(toolId) ? toolId.trim() : "";
        if (normalizedRunId.isBlank() || normalizedToolId.isBlank()) {
            throw new IllegalArgumentException("runId and toolId are required");
        }
        return normalizedRunId + "#" + normalizedToolId;
    }
}
