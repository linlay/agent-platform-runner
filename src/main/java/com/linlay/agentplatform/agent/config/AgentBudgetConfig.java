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
        return toBudget(Budget.DEFAULT);
    }

    public Budget toBudget(Budget defaults) {
        if (!unknownFields.isEmpty()) {
            List<String> names = unknownFields.keySet().stream().sorted().toList();
            throw new IllegalArgumentException(
                    "budget contains unsupported fields: " + String.join(", ", names)
            );
        }
        Budget base = defaults == null ? Budget.DEFAULT : defaults;
        return new Budget(
                runTimeoutMs == null ? base.runTimeoutMs() : runTimeoutMs,
                mergeScope(model, base.model()),
                mergeScope(tool, base.tool())
        );
    }

    private Budget.Scope mergeScope(ScopeConfig scope, Budget.Scope defaults) {
        if (scope == null) {
            return defaults;
        }
        Budget.Scope base = defaults == null ? Budget.DEFAULT.model() : defaults;
        return new Budget.Scope(
                scope.maxCalls == null ? base.maxCalls() : scope.maxCalls,
                scope.timeoutMs == null ? base.timeoutMs() : scope.timeoutMs,
                scope.retryCount == null ? base.retryCount() : scope.retryCount
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
    }
}
