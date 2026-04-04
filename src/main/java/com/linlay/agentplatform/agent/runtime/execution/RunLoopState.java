package com.linlay.agentplatform.agent.runtime.execution;

public enum RunLoopState {
    IDLE,
    MODEL_STREAMING,
    TOOL_EXECUTING,
    WAITING_SUBMIT,
    COMPLETED,
    CANCELLED,
    FAILED
}
