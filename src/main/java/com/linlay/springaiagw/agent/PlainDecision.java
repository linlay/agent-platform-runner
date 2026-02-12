package com.linlay.springaiagw.agent;

record PlainDecision(
        String thinking,
        PlannedToolCall toolCall,
        boolean valid
) {
}
