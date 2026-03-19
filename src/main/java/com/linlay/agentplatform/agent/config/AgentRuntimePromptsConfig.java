package com.linlay.agentplatform.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRuntimePromptsConfig {
    private PlanExecutePromptConfig planExecute;
    private SkillPromptConfig skill;
    private ToolAppendixPromptConfig toolAppendix;

    public PlanExecutePromptConfig getPlanExecute() {
        return planExecute;
    }

    public void setPlanExecute(PlanExecutePromptConfig planExecute) {
        this.planExecute = planExecute;
    }

    public SkillPromptConfig getSkill() {
        return skill;
    }

    public void setSkill(SkillPromptConfig skill) {
        this.skill = skill;
    }

    public ToolAppendixPromptConfig getToolAppendix() {
        return toolAppendix;
    }

    public void setToolAppendix(ToolAppendixPromptConfig toolAppendix) {
        this.toolAppendix = toolAppendix;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanExecutePromptConfig {
        private String taskExecutionPromptTemplate;

        public String getTaskExecutionPromptTemplate() {
            return taskExecutionPromptTemplate;
        }

        public void setTaskExecutionPromptTemplate(String taskExecutionPromptTemplate) {
            this.taskExecutionPromptTemplate = taskExecutionPromptTemplate;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillPromptConfig {
        private String catalogHeader;
        private String disclosureHeader;
        private String instructionsLabel;

        public String getCatalogHeader() {
            return catalogHeader;
        }

        public void setCatalogHeader(String catalogHeader) {
            this.catalogHeader = catalogHeader;
        }

        public String getDisclosureHeader() {
            return disclosureHeader;
        }

        public void setDisclosureHeader(String disclosureHeader) {
            this.disclosureHeader = disclosureHeader;
        }

        public String getInstructionsLabel() {
            return instructionsLabel;
        }

        public void setInstructionsLabel(String instructionsLabel) {
            this.instructionsLabel = instructionsLabel;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolAppendixPromptConfig {
        private String toolDescriptionTitle;
        private String afterCallHintTitle;

        public String getToolDescriptionTitle() {
            return toolDescriptionTitle;
        }

        public void setToolDescriptionTitle(String toolDescriptionTitle) {
            this.toolDescriptionTitle = toolDescriptionTitle;
        }

        public String getAfterCallHintTitle() {
            return afterCallHintTitle;
        }

        public void setAfterCallHintTitle(String afterCallHintTitle) {
            this.afterCallHintTitle = afterCallHintTitle;
        }
    }
}
