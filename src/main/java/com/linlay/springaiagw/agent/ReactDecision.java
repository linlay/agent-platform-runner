package com.linlay.springaiagw.agent;

record ReactDecision(
        String thinking,
        PlannedToolCall action,
        boolean done
) {
}
