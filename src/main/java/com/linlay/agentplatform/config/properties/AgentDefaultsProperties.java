package com.linlay.agentplatform.config.properties;

import com.linlay.agentplatform.engine.policy.Budget;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.defaults")
public class AgentDefaultsProperties {

    private int maxTokens = 4096;
    private final BudgetProperties budget = new BudgetProperties();
    private final ReactProperties react = new ReactProperties();
    private final PlanExecuteProperties planExecute = new PlanExecuteProperties();

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public BudgetProperties getBudget() {
        return budget;
    }

    public ReactProperties getReact() {
        return react;
    }

    public PlanExecuteProperties getPlanExecute() {
        return planExecute;
    }

    public Budget defaultBudget() {
        return new Budget(
                budget.getRunTimeoutMs(),
                new Budget.Scope(
                        budget.getModel().getMaxCalls(),
                        budget.getModel().getTimeoutMs(),
                        budget.getModel().getRetryCount()
                ),
                new Budget.Scope(
                        budget.getTool().getMaxCalls(),
                        budget.getTool().getTimeoutMs(),
                        budget.getTool().getRetryCount()
                )
        );
    }

    public int defaultMaxTokens() {
        return maxTokens > 0 ? maxTokens : 4096;
    }

    public int defaultReactMaxSteps() {
        return react.getMaxSteps() > 0 ? react.getMaxSteps() : 60;
    }

    public int defaultPlanExecuteMaxSteps() {
        return planExecute.getMaxSteps() > 0 ? planExecute.getMaxSteps() : 60;
    }

    public static class BudgetProperties {
        private long runTimeoutMs = 300_000L;
        private final ScopeProperties model = new ScopeProperties(30, 120_000L, 0);
        private final ScopeProperties tool = new ScopeProperties(20, 120_000L, 0);

        public long getRunTimeoutMs() {
            return runTimeoutMs;
        }

        public void setRunTimeoutMs(long runTimeoutMs) {
            this.runTimeoutMs = runTimeoutMs;
        }

        public ScopeProperties getModel() {
            return model;
        }

        public ScopeProperties getTool() {
            return tool;
        }
    }

    public static class ScopeProperties {
        private int maxCalls;
        private long timeoutMs;
        private int retryCount;

        public ScopeProperties() {
        }

        public ScopeProperties(int maxCalls, long timeoutMs, int retryCount) {
            this.maxCalls = maxCalls;
            this.timeoutMs = timeoutMs;
            this.retryCount = retryCount;
        }

        public int getMaxCalls() {
            return maxCalls;
        }

        public void setMaxCalls(int maxCalls) {
            this.maxCalls = maxCalls;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
    }

    public static class ReactProperties {
        private int maxSteps = 60;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }
    }

    public static class PlanExecuteProperties {
        private int maxSteps = 60;
        private int maxWorkRoundsPerTask = 6;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxWorkRoundsPerTask() {
            return maxWorkRoundsPerTask;
        }

        public void setMaxWorkRoundsPerTask(int maxWorkRoundsPerTask) {
            this.maxWorkRoundsPerTask = maxWorkRoundsPerTask;
        }
    }

    public int defaultPlanExecuteMaxWorkRoundsPerTask() {
        int configured = planExecute.getMaxWorkRoundsPerTask();
        return configured > 0 ? configured : 6;
    }
}
