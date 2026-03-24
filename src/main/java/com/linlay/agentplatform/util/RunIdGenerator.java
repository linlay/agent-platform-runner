package com.linlay.agentplatform.util;

public final class RunIdGenerator {

    private RunIdGenerator() {
    }

    public static String nextRunId() {
        return encodeEpochMillis(System.currentTimeMillis());
    }

    public static String encodeEpochMillis(long epochMillis) {
        long normalized = epochMillis > 0 ? epochMillis : System.currentTimeMillis();
        return Long.toString(normalized, 36);
    }
}
