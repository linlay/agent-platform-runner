package com.linlay.agentplatform.agent.runtime;

import org.springframework.util.StringUtils;

import java.util.Objects;

final class ToolTrace {

    final String toolCallId;
    String toolName;
    String toolType;
    private final StringBuilder arguments = new StringBuilder();
    long firstSeenAt;
    long resultAt;
    boolean recorded;

    ToolTrace(String toolCallId) {
        this.toolCallId = Objects.requireNonNull(toolCallId);
    }

    void appendArguments(String delta) {
        if (StringUtils.hasText(delta)) {
            arguments.append(delta);
        }
    }

    String arguments() {
        return arguments.isEmpty() ? null : arguments.toString();
    }
}
