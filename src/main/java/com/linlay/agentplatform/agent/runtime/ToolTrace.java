package com.linlay.agentplatform.agent.runtime;

import org.springframework.util.StringUtils;

import java.util.Objects;

public final class ToolTrace {

    public final String toolCallId;
    public String toolName;
    public String toolType;
    private final StringBuilder arguments = new StringBuilder();
    public long firstSeenAt;
    public long resultAt;
    public boolean recorded;

    public ToolTrace(String toolCallId) {
        this.toolCallId = Objects.requireNonNull(toolCallId);
    }

    public void appendArguments(String delta) {
        if (StringUtils.hasText(delta)) {
            arguments.append(delta);
        }
    }

    public String arguments() {
        return arguments.isEmpty() ? null : arguments.toString();
    }
}
