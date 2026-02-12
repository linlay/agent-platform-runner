package com.linlay.springaiagw.agent;

import java.util.Objects;

final class NativeToolCall {
    final String toolId;
    String toolName;
    final StringBuilder arguments = new StringBuilder();

    NativeToolCall(String toolId) {
        this.toolId = Objects.requireNonNull(toolId);
    }
}
