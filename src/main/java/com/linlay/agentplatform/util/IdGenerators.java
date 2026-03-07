package com.linlay.agentplatform.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class IdGenerators {

    private IdGenerators() {
    }

    public static String shortHexId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();
    }

    public static String shortHexId(String prefix) {
        if (!StringHelpers.hasText(prefix)) {
            return shortHexId();
        }
        return prefix + "_" + shortHexId();
    }

    public static String shortHexId(String prefix, String seed) {
        String shortPart = StringHelpers.hasText(seed)
                ? UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toLowerCase()
                : shortHexId();
        if (!StringHelpers.hasText(prefix)) {
            return shortPart;
        }
        return prefix + "_" + shortPart;
    }

    public static String toolCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
