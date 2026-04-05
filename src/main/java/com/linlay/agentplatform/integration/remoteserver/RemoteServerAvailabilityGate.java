package com.linlay.agentplatform.integration.remoteserver;

import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RemoteServerAvailabilityGate {

    private final Clock clock;
    private final long reconnectIntervalMs;
    private final Map<String, FailureState> failureStates = new ConcurrentHashMap<>();

    protected RemoteServerAvailabilityGate(Clock clock, long reconnectIntervalMs) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.reconnectIntervalMs = Math.max(1L, reconnectIntervalMs);
    }

    public boolean isBlocked(String serverKey) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return false;
        }
        FailureState failureState = failureStates.get(normalizedKey);
        return failureState != null && Instant.now(clock).isBefore(failureState.nextRetryAt());
    }

    public Set<String> readyToRetry(Collection<String> serverKeys) {
        if (serverKeys == null || serverKeys.isEmpty()) {
            return Set.of();
        }
        Instant now = Instant.now(clock);
        Set<String> ready = new LinkedHashSet<>();
        for (String serverKey : serverKeys) {
            String normalizedKey = normalize(serverKey);
            if (!StringUtils.hasText(normalizedKey)) {
                continue;
            }
            FailureState failureState = failureStates.get(normalizedKey);
            if (failureState != null && !now.isBefore(failureState.nextRetryAt())) {
                ready.add(normalizedKey);
            }
        }
        return Set.copyOf(ready);
    }

    public void markFailure(String serverKey) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return;
        }
        Instant nextRetryAt = Instant.now(clock).plusMillis(reconnectIntervalMs);
        failureStates.put(normalizedKey, new FailureState(nextRetryAt));
    }

    public void markSuccess(String serverKey) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return;
        }
        failureStates.remove(normalizedKey);
    }

    public void prune(Set<String> activeServerKeys) {
        if (activeServerKeys == null || activeServerKeys.isEmpty()) {
            failureStates.clear();
            return;
        }
        Set<String> normalizedActiveKeys = activeServerKeys.stream()
                .map(this::normalize)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        failureStates.keySet().removeIf(key -> !normalizedActiveKeys.contains(key));
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private record FailureState(Instant nextRetryAt) {
    }
}
