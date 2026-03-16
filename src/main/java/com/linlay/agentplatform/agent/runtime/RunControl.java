package com.linlay.agentplatform.agent.runtime;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RunControl {

    private final RunInputBroker inputBroker = new RunInputBroker();
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final Sinks.One<Void> cancelSink = Sinks.one();
    private final AtomicReference<Thread> runnerThread = new AtomicReference<>();
    private final AtomicReference<RunLoopState> state = new AtomicReference<>(RunLoopState.IDLE);

    public void enqueueQuery(RunInputBroker.QueryEnvelope query) {
        if (query == null || interrupted.get()) {
            return;
        }
        inputBroker.enqueueQuery(query);
    }

    public void enqueueSteer(SteerEnvelope steer) {
        if (steer == null || interrupted.get()) {
            return;
        }
        inputBroker.enqueueSteer(new RunInputBroker.SteerEnvelope(steer.requestId(), steer.steerId(), steer.message()));
    }

    public List<SteerEnvelope> drainPendingSteers() {
        return inputBroker.drainPendingSteers().stream()
                .map(steer -> new SteerEnvelope(steer.requestId(), steer.steerId(), steer.message()))
                .toList();
    }

    public void interrupt() {
        interrupt(null, null);
    }

    public void interrupt(String requestId, String message) {
        if (!interrupted.compareAndSet(false, true)) {
            return;
        }
        inputBroker.enqueueInterrupt(new RunInputBroker.InterruptEnvelope(requestId, message));
        cancelSink.tryEmitEmpty();
        inputBroker.clearPendingSteers();
        inputBroker.cancelPendingSubmits("Run interrupted");
        state.set(RunLoopState.CANCELLED);
        interruptRunnerThread();
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

    public Object awaitSubmit(String runId, String toolId, long timeoutMs) throws TimeoutException {
        if (interrupted.get()) {
            throw new CancellationException("Run interrupted: runId=" + runId);
        }
        return inputBroker.awaitSubmit(toolId, java.time.Duration.ofMillis(Math.max(1L, timeoutMs)), cancelSignal());
    }

    public RunInputBroker.SubmitAck submit(String toolId, Object payload) {
        return inputBroker.submit(toolId, payload);
    }

    public RunLoopState state() {
        return state.get();
    }

    public void transitionState(RunLoopState next) {
        if (next != null) {
            state.set(next);
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
