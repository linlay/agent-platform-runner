package com.linlay.agentplatform.agent.runtime;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RunControl {

    private final Queue<SteerEnvelope> pendingSteers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final Sinks.One<Void> cancelSink = Sinks.one();
    private final AtomicReference<Thread> runnerThread = new AtomicReference<>();

    public void enqueueSteer(SteerEnvelope steer) {
        if (steer == null || interrupted.get()) {
            return;
        }
        pendingSteers.add(steer);
    }

    public List<SteerEnvelope> drainPendingSteers() {
        List<SteerEnvelope> drained = new ArrayList<>();
        while (true) {
            SteerEnvelope next = pendingSteers.poll();
            if (next == null) {
                return List.copyOf(drained);
            }
            drained.add(next);
        }
    }

    public void interrupt() {
        if (!interrupted.compareAndSet(false, true)) {
            return;
        }
        cancelSink.tryEmitEmpty();
        interruptRunnerThread();
        pendingSteers.clear();
    }

    public boolean isInterrupted() {
        return interrupted.get();
    }

    public Mono<Void> cancelSignal() {
        return cancelSink.asMono();
    }

    public void bindRunnerThread(Thread thread) {
        if (thread == null) {
            runnerThread.set(null);
            return;
        }
        runnerThread.set(thread);
    }

    public void interruptRunnerThread() {
        Thread thread = runnerThread.get();
        if (thread != null) {
            thread.interrupt();
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

        private static String requireText(String value, String fieldName) {
            String normalized = normalize(value);
            if (normalized == null) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim();
            return normalized.isEmpty() ? null : normalized;
        }
    }
}
