package com.linlay.agentplatform.agent;

import java.util.Map;

public record PlannedToolCall(
        String name,
        Map<String, Object> arguments,
        String callId
) {
}
