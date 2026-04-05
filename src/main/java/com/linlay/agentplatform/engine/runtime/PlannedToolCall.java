package com.linlay.agentplatform.engine.runtime;

import java.util.Map;

public record PlannedToolCall(
        String name,
        Map<String, Object> arguments,
        String callId
) {
}
