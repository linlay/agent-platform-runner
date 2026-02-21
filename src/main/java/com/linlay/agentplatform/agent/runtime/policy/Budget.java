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

    private static final Scope DEFAULT_MODEL_SCOPE = new Scope(15, 60_000, 0);
    private static final Scope DEFAULT_TOOL_SCOPE = new Scope(20, 120_000, 0);
    private static final long DEFAULT_RUN_TIMEOUT_MS = 120_000L;
    private static final Scope LIGHT_MODEL_SCOPE = new Scope(3, 30_000, 0);
    private static final Scope LIGHT_TOOL_SCOPE = new Scope(5, 30_000, 0);
    private static final Scope HEAVY_MODEL_SCOPE = new Scope(30, 120_000, 0);
    private static final Scope HEAVY_TOOL_SCOPE = new Scope(50, 300_000, 0);

    public static final Budget DEFAULT = new Budget(DEFAULT_RUN_TIMEOUT_MS, DEFAULT_MODEL_SCOPE, DEFAULT_TOOL_SCOPE);
    public static final Budget LIGHT = new Budget(30_000, LIGHT_MODEL_SCOPE, LIGHT_TOOL_SCOPE);
    public static final Budget HEAVY = new Budget(300_000, HEAVY_MODEL_SCOPE, HEAVY_TOOL_SCOPE);

    /**
     * Backward-compatible constructor for in-repo test fixtures.
     * Legacy fields map to:
     * - runTimeoutMs = timeoutMs
     * - model = {maxModelCalls, timeoutMs, retryCount}
     * - tool  = {maxToolCalls, timeoutMs, retryCount}
     */
    public Budget(
            int maxModelCalls,
            int maxToolCalls,
            long timeoutMs,
            int retryCount
    ) {
        this(
                timeoutMs,
                new Scope(maxModelCalls, timeoutMs, retryCount),
                new Scope(maxToolCalls, timeoutMs, retryCount)
        );
    }

    public Budget {
        runTimeoutMs = runTimeoutMs > 0 ? runTimeoutMs : DEFAULT_RUN_TIMEOUT_MS;
        model = normalizeScope(model, DEFAULT_MODEL_SCOPE);
        tool = normalizeScope(tool, DEFAULT_TOOL_SCOPE);
    }

    @Deprecated(forRemoval = false)
    public int maxModelCalls() {
        return model.maxCalls();
    }

    @Deprecated(forRemoval = false)
    public int maxToolCalls() {
        return tool.maxCalls();
    }

    @Deprecated(forRemoval = false)
    public long timeoutMs() {
        return runTimeoutMs;
    }

    @Deprecated(forRemoval = false)
    public int retryCount() {
        return model.retryCount();
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
