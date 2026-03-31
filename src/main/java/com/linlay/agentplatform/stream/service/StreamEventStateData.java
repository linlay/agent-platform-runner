package com.linlay.agentplatform.stream.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class StreamEventStateData {

    private String planId;
    private String activeTaskId;
    private String activeReasoningId;
    private String activeContentId;

    private final Set<String> knownToolIds = new LinkedHashSet<>();
    private final Set<String> openToolIds = new LinkedHashSet<>();
    private final Map<String, AtomicInteger> toolArgChunkCounters = new HashMap<>();

    private final Set<String> knownActionIds = new LinkedHashSet<>();
    private final Set<String> openActionIds = new LinkedHashSet<>();

    private boolean terminated;

    String planId() {
        return planId;
    }

    void setPlanId(String planId) {
        this.planId = planId;
    }

    String activeTaskId() {
        return activeTaskId;
    }

    void setActiveTaskId(String activeTaskId) {
        this.activeTaskId = activeTaskId;
    }

    String activeReasoningId() {
        return activeReasoningId;
    }

    void setActiveReasoningId(String activeReasoningId) {
        this.activeReasoningId = activeReasoningId;
    }

    String activeContentId() {
        return activeContentId;
    }

    void setActiveContentId(String activeContentId) {
        this.activeContentId = activeContentId;
    }

    boolean hasKnownTool(String toolId) {
        return knownToolIds.contains(toolId);
    }

    void rememberOpenTool(String toolId) {
        knownToolIds.add(toolId);
        openToolIds.add(toolId);
    }

    boolean isToolOpen(String toolId) {
        return openToolIds.contains(toolId);
    }

    boolean closeTool(String toolId) {
        return openToolIds.remove(toolId);
    }

    List<String> openToolIds() {
        return List.copyOf(openToolIds);
    }

    int nextToolArgChunkIndex(String toolId) {
        return toolArgChunkCounters.computeIfAbsent(toolId, key -> new AtomicInteger(0)).getAndIncrement();
    }

    boolean hasKnownAction(String actionId) {
        return knownActionIds.contains(actionId);
    }

    void rememberOpenAction(String actionId) {
        knownActionIds.add(actionId);
        openActionIds.add(actionId);
    }

    boolean isActionOpen(String actionId) {
        return openActionIds.contains(actionId);
    }

    boolean closeAction(String actionId) {
        return openActionIds.remove(actionId);
    }

    List<String> openActionIds() {
        return List.copyOf(openActionIds);
    }

    boolean terminated() {
        return terminated;
    }

    void setTerminated(boolean terminated) {
        this.terminated = terminated;
    }
}
