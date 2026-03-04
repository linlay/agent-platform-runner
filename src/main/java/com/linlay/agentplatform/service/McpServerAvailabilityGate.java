package com.linlay.agentplatform.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpServerAvailabilityGate {

    private final Map<String, Long> blockedServerVersion = new ConcurrentHashMap<>();

    public boolean isBlocked(String serverKey, long registryVersion) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return false;
        }
        Long blockedVersion = blockedServerVersion.get(normalizedKey);
        return blockedVersion != null && blockedVersion == registryVersion;
    }

    public void markFailure(String serverKey, long registryVersion) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return;
        }
        blockedServerVersion.put(normalizedKey, registryVersion);
    }

    public void markSuccess(String serverKey) {
        String normalizedKey = normalize(serverKey);
        if (!StringUtils.hasText(normalizedKey)) {
            return;
        }
        blockedServerVersion.remove(normalizedKey);
    }

    public void prune(Set<String> activeServerKeys) {
        if (activeServerKeys == null || activeServerKeys.isEmpty()) {
            blockedServerVersion.clear();
            return;
        }
        Set<String> normalizedActiveKeys = activeServerKeys.stream()
                .map(this::normalize)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        blockedServerVersion.keySet().removeIf(key -> !normalizedActiveKeys.contains(key));
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
