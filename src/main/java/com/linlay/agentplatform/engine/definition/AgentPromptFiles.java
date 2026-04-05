package com.linlay.agentplatform.engine.definition;

public record AgentPromptFiles(
        String soulContent,
        String agentsContent,
        String plainStageContent,
        String reactStageContent,
        String planStageContent,
        String executeStageContent,
        String summaryStageContent
) {

    public static AgentPromptFiles empty() {
        return new AgentPromptFiles(null, null, null, null, null, null, null);
    }
}
