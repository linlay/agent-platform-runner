package com.linlay.agentplatform.util;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CatalogDiff(
        Set<String> addedKeys,
        Set<String> removedKeys,
        Set<String> updatedKeys
) {
    public CatalogDiff {
        addedKeys = addedKeys == null ? Set.of() : Set.copyOf(addedKeys);
        removedKeys = removedKeys == null ? Set.of() : Set.copyOf(removedKeys);
        updatedKeys = updatedKeys == null ? Set.of() : Set.copyOf(updatedKeys);
    }

    public static <T> CatalogDiff between(Map<String, T> before, Map<String, T> after) {
        Map<String, T> previous = before == null ? Map.of() : before;
        Map<String, T> next = after == null ? Map.of() : after;

        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> updated = new LinkedHashSet<>();

        for (String key : next.keySet()) {
            if (!previous.containsKey(key)) {
                added.add(key);
                continue;
            }
            if (!Objects.equals(previous.get(key), next.get(key))) {
                updated.add(key);
            }
        }

        for (String key : previous.keySet()) {
            if (!next.containsKey(key)) {
                removed.add(key);
            }
        }

        return new CatalogDiff(added, removed, updated);
    }

    public Set<String> changedKeys() {
        Set<String> changed = new LinkedHashSet<>();
        changed.addAll(addedKeys);
        changed.addAll(removedKeys);
        changed.addAll(updatedKeys);
        return Set.copyOf(changed);
    }

    public boolean isEmpty() {
        return addedKeys.isEmpty() && removedKeys.isEmpty() && updatedKeys.isEmpty();
    }
}
