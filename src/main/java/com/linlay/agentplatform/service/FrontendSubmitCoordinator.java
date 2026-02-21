package com.linlay.agentplatform.service;

import com.linlay.agentplatform.config.FrontendToolProperties;
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

    public SubmitAck submit(String runId, String toolId, Object params) {
        String key = key(runId, toolId);
        CompletableFuture<Object> pending = pendingByKey.remove(key);
        if (pending == null) {
            return new SubmitAck(
                    false,
                    "unmatched",
                    "No pending frontend tool found for runId=" + runId + ", toolId=" + toolId
            );
        }
        pending.complete(params == null ? Map.of() : params);
        return new SubmitAck(
                true,
                "accepted",
                "Frontend submit accepted for runId=" + runId + ", toolId=" + toolId
        );
    }

    private String key(String runId, String toolId) {
        String normalizedRunId = StringUtils.hasText(runId) ? runId.trim() : "";
        String normalizedToolId = StringUtils.hasText(toolId) ? toolId.trim() : "";
        if (normalizedRunId.isBlank() || normalizedToolId.isBlank()) {
            throw new IllegalArgumentException("runId and toolId are required");
        }
        return normalizedRunId + "#" + normalizedToolId;
    }

    public record SubmitAck(
            boolean accepted,
            String status,
            String detail
    ) {
    }
}
