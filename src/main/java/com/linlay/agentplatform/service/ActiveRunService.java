package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.runtime.RunControl;
import com.linlay.agentplatform.model.api.InterruptRequest;
import com.linlay.agentplatform.model.api.SteerRequest;
import com.linlay.agentplatform.stream.model.StreamInput;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ActiveRunService {

    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final Map<String, ActiveRunSession> sessionsByRunId = new ConcurrentHashMap<>();

    public ActiveRunService(FrontendSubmitCoordinator frontendSubmitCoordinator) {
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
    }

    public ActiveRunSession register(String runId, String chatId, String agentKey) {
        String normalizedRunId = normalizeRequired(runId, "runId");
        ActiveRunSession session = new ActiveRunSession(normalizedRunId, normalize(chatId), normalize(agentKey));
        sessionsByRunId.put(normalizedRunId, session);
        return session;
    }

    public Optional<RunControl> findControl(String runId) {
        ActiveRunSession session = sessionsByRunId.get(normalize(runId));
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.control());
    }

    public SteerAck steer(SteerRequest request) {
        String runId = normalizeRequired(request.runId(), "runId");
        ActiveRunSession session = sessionsByRunId.get(runId);
        if (session == null || !session.markActiveForSteer()) {
            return new SteerAck(false, "unmatched", runId, resolveSteerId(request.steerId()), "No active run found for runId=" + runId);
        }
        String steerId = resolveSteerId(request.steerId());
        String message = normalizeRequired(request.message(), "message");
        session.control().enqueueSteer(new RunControl.SteerEnvelope(request.requestId(), steerId, message));
        return new SteerAck(true, "accepted", runId, steerId, "Steer accepted for runId=" + runId + ", steerId=" + steerId);
    }

    public InterruptAck interrupt(InterruptRequest request) {
        String runId = normalizeRequired(request.runId(), "runId");
        ActiveRunSession session = sessionsByRunId.get(runId);
        if (session == null || !session.cancel()) {
            return new InterruptAck(false, "unmatched", runId, "No active run found for runId=" + runId);
        }
        session.emit(new StreamInput.RunCancel(runId));
        session.control().interrupt();
        if (frontendSubmitCoordinator != null) {
            frontendSubmitCoordinator.cancelRun(runId);
        }
        session.completeInjectedInputs();
        return new InterruptAck(true, "accepted", runId, "Interrupt accepted for runId=" + runId);
    }

    public void finish(String runId) {
        String normalizedRunId = normalize(runId);
        if (!StringUtils.hasText(normalizedRunId)) {
            return;
        }
        ActiveRunSession removed = sessionsByRunId.remove(normalizedRunId);
        if (removed != null) {
            removed.complete();
        }
    }

    private String resolveSteerId(String steerId) {
        String normalized = normalize(steerId);
        return StringUtils.hasText(normalized) ? normalized : UUID.randomUUID().toString();
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalize(value);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record SteerAck(
            boolean accepted,
            String status,
            String runId,
            String steerId,
            String detail
    ) {
    }

    public record InterruptAck(
            boolean accepted,
            String status,
            String runId,
            String detail
    ) {
    }

    public static final class ActiveRunSession {

        private final String runId;
        private final String chatId;
        private final String agentKey;
        private final RunControl control;
        private final Sinks.Many<StreamInput> injectedInputs;
        private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.ACTIVE);

        private ActiveRunSession(String runId, String chatId, String agentKey) {
            this.runId = runId;
            this.chatId = chatId;
            this.agentKey = agentKey;
            this.control = new RunControl();
            this.injectedInputs = Sinks.many().unicast().onBackpressureBuffer();
        }

        public String runId() {
            return runId;
        }

        public String chatId() {
            return chatId;
        }

        public String agentKey() {
            return agentKey;
        }

        public RunControl control() {
            return control;
        }

        public Flux<StreamInput> injectedInputs() {
            return injectedInputs.asFlux();
        }

        public boolean markActiveForSteer() {
            return state.get() == LifecycleState.ACTIVE && !control.isInterrupted();
        }

        public boolean cancel() {
            return state.compareAndSet(LifecycleState.ACTIVE, LifecycleState.CANCELLED);
        }

        public void emit(StreamInput input) {
            if (input == null) {
                return;
            }
            injectedInputs.tryEmitNext(input);
        }

        public void completeInjectedInputs() {
            injectedInputs.tryEmitComplete();
        }

        public void complete() {
            state.compareAndSet(LifecycleState.ACTIVE, LifecycleState.COMPLETED);
            state.compareAndSet(LifecycleState.CANCELLED, LifecycleState.COMPLETED);
            injectedInputs.tryEmitComplete();
        }
    }

    private enum LifecycleState {
        ACTIVE,
        CANCELLED,
        COMPLETED
    }
}
