package com.linlay.agentplatform.agent.runtime.policy;

public record Budget(
        long runTimeoutMs,
        Scope model,
        Scope tool
) {

    public record Scope(
            int maxCalls,
            long timeoutMs,
            int retryCount
    ) {
        public Scope {
            if (retryCount < 0) {
                retryCount = 0;
            }
        }
    }

    private static final Scope DEFAULT_MODEL_SCOPE = new Scope(30, 120_000, 0);
    private static final Scope DEFAULT_TOOL_SCOPE = new Scope(50, 300_000, 0);
    private static final long DEFAULT_RUN_TIMEOUT_MS = 300_000L;

    public static final Budget DEFAULT = new Budget(DEFAULT_RUN_TIMEOUT_MS, DEFAULT_MODEL_SCOPE, DEFAULT_TOOL_SCOPE);

    public Budget {
        runTimeoutMs = runTimeoutMs > 0 ? runTimeoutMs : DEFAULT_RUN_TIMEOUT_MS;
        model = normalizeScope(model, DEFAULT_MODEL_SCOPE);
        tool = normalizeScope(tool, DEFAULT_TOOL_SCOPE);
    }

    private Scope normalizeScope(Scope input, Scope fallback) {
        if (input == null) {
            return fallback;
        }
        int maxCalls = input.maxCalls() > 0 ? input.maxCalls() : fallback.maxCalls();
        long timeoutMs = input.timeoutMs() > 0 ? input.timeoutMs() : fallback.timeoutMs();
        int retryCount = Math.max(0, input.retryCount());
        return new Scope(maxCalls, timeoutMs, retryCount);
    }
}
