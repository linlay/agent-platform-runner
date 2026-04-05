package com.linlay.agentplatform.engine.runtime;

import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

public final class RunInputBroker {

    private final Queue<QueryEnvelope> pendingQueries = new ConcurrentLinkedQueue<>();
    private final Queue<SteerEnvelope> pendingSteers = new ConcurrentLinkedQueue<>();
    private final Queue<InterruptEnvelope> pendingInterrupts = new ConcurrentLinkedQueue<>();
    private final Map<String, CompletableFuture<Object>> submitWaiters = new ConcurrentHashMap<>();
    private final Map<String, Object> bufferedSubmits = new ConcurrentHashMap<>();

    public void enqueueQuery(QueryEnvelope query) {
        if (query != null) {
            pendingQueries.add(query);
        }
    }

    public List<QueryEnvelope> drainPendingQueries() {
        return drain(pendingQueries);
    }

    public void enqueueSteer(SteerEnvelope steer) {
        if (steer != null) {
            pendingSteers.add(steer);
        }
    }

    public List<SteerEnvelope> drainPendingSteers() {
        return drain(pendingSteers);
    }

    public void enqueueInterrupt(InterruptEnvelope interrupt) {
        if (interrupt != null) {
            pendingInterrupts.add(interrupt);
        }
    }

    public InterruptEnvelope pollInterrupt() {
        return pendingInterrupts.poll();
    }

    public boolean hasPendingInterrupt() {
        return !pendingInterrupts.isEmpty();
    }

    public Object awaitSubmit(String toolId, Duration timeout, Mono<Void> cancelSignal) throws TimeoutException {
        String normalizedToolId = requireText(toolId, "toolId");
        Object buffered = bufferedSubmits.remove(normalizedToolId);
        if (buffered != null) {
            return buffered;
        }

        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existed = submitWaiters.putIfAbsent(normalizedToolId, future);
        if (existed != null) {
            future = existed;
        }

        Disposable cancelSubscription = subscribeCancel(cancelSignal, future, normalizedToolId);
        try {
            long timeoutMs = timeout == null ? 0L : timeout.toMillis();
            return Mono.fromFuture(future)
                    .timeout(Duration.ofMillis(Math.max(1L, timeoutMs <= 0L ? 1L : timeoutMs)))
                    .onErrorMap(
                            TimeoutException.class,
                            ex -> new TimeoutException("Frontend tool submit timeout toolId=" + normalizedToolId)
                    )
                    .block();
        } finally {
            cancelSubscription.dispose();
            submitWaiters.remove(normalizedToolId, future);
        }
    }

    public SubmitAck submit(String toolId, Object payload) {
        String normalizedToolId = requireText(toolId, "toolId");
        Object normalizedPayload = payload == null ? Map.of() : payload;
        CompletableFuture<Object> waiter = submitWaiters.remove(normalizedToolId);
        if (waiter != null) {
            waiter.complete(normalizedPayload);
            return new SubmitAck(true, "accepted");
        }
        bufferedSubmits.put(normalizedToolId, normalizedPayload);
        return new SubmitAck(false, "buffered");
    }

    public void cancelPendingSubmits(String detail) {
        CancellationException cancellation = new CancellationException(detail);
        submitWaiters.forEach((toolId, future) -> {
            if (submitWaiters.remove(toolId, future)) {
                future.completeExceptionally(cancellation);
            }
        });
        bufferedSubmits.clear();
    }

    public void clearPendingSteers() {
        pendingSteers.clear();
    }

    private <T> List<T> drain(Queue<T> queue) {
        List<T> drained = new ArrayList<>();
        while (true) {
            T next = queue.poll();
            if (next == null) {
                return List.copyOf(drained);
            }
            drained.add(next);
        }
    }

    private Disposable subscribeCancel(Mono<Void> cancelSignal, CompletableFuture<Object> future, String toolId) {
        if (cancelSignal == null) {
            return () -> {};
        }
        return cancelSignal.subscribe(
                ignored -> { },
                future::completeExceptionally,
                () -> future.completeExceptionally(new CancellationException("Run interrupted while waiting for submit toolId=" + toolId))
        );
    }

    public record QueryEnvelope(
            String requestId,
            String message
    ) {
        public QueryEnvelope {
            message = requireText(message, "message");
            requestId = normalize(requestId);
        }
    }

    public record SteerEnvelope(
            String requestId,
            String steerId,
            String message
    ) {
        public SteerEnvelope {
            steerId = requireText(steerId, "steerId");
            message = requireText(message, "message");
            requestId = normalize(requestId);
        }
    }

    public record InterruptEnvelope(
            String requestId,
            String message
    ) {
        public InterruptEnvelope {
            requestId = normalize(requestId);
            message = normalize(message);
        }
    }

    public record SubmitAck(
            boolean accepted,
            String status
    ) {
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
