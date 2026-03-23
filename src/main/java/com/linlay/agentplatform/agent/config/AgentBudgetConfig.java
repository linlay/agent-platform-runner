package com.linlay.agentplatform.agent.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.linlay.agentplatform.agent.runtime.policy.Budget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentBudgetConfig {

    private Long runTimeoutMs;
    private ScopeConfig model;
    private ScopeConfig tool;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    public Long getRunTimeoutMs() {
        return runTimeoutMs;
    }

    public void setRunTimeoutMs(Long runTimeoutMs) {
        this.runTimeoutMs = runTimeoutMs;
    }

    public ScopeConfig getModel() {
        return model;
    }

    public void setModel(ScopeConfig model) {
        this.model = model;
    }

    public ScopeConfig getTool() {
        return tool;
    }

    public void setTool(ScopeConfig tool) {
        this.tool = tool;
    }

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        unknownFields.put(key, value);
    }

    public Map<String, Object> getUnknownFields() {
        return Map.copyOf(unknownFields);
    }

    public Budget toBudget() {
        if (!unknownFields.isEmpty()) {
            List<String> names = unknownFields.keySet().stream().sorted().toList();
            throw new IllegalArgumentException(
                    "budget contains unsupported fields: " + String.join(", ", names)
            );
        }
        return new Budget(
                runTimeoutMs == null ? 0L : runTimeoutMs,
                model == null ? null : model.toScope(),
                tool == null ? null : tool.toScope()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScopeConfig {
        private Integer maxCalls;
        private Long timeoutMs;
        private Integer retryCount;

        public Integer getMaxCalls() {
            return maxCalls;
        }

        public void setMaxCalls(Integer maxCalls) {
            this.maxCalls = maxCalls;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(Integer retryCount) {
            this.retryCount = retryCount;
        }

        private Budget.Scope toScope() {
            return new Budget.Scope(
                    maxCalls == null ? 0 : maxCalls,
                    timeoutMs == null ? 0L : timeoutMs,
                    retryCount == null ? 0 : retryCount
            );
        }
    }
}
