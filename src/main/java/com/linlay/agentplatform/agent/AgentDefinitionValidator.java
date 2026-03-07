package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;

public final class AgentDefinitionValidator {

    private AgentDefinitionValidator() {
    }

    public static boolean isLegacyConfig(JsonNode root) {
        if (root == null || !root.isObject()) {
            return true;
        }
        if (root.has("deepThink")
                || root.has("systemPrompt")
                || root.has("providerKey")
                || root.has("model")
                || root.has("reasoning")
                || root.has("tools")) {
            return true;
        }
        return hasLegacyStageFields(root.path("plain"))
                || hasLegacyStageFields(root.path("react"))
                || hasLegacyPlanExecuteStageFields(root.path("planExecute"));
    }

    public static boolean hasRemovedFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (hasLegacyModelConfigFields(root.path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("plain").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("react").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("plan").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("execute").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("summary").path("modelConfig"))) {
            return true;
        }
        if (root.has("verify") || root.has("output") || root.has("toolPolicy")) {
            return true;
        }
        JsonNode budget = root.path("budget");
        if (budget.isObject() && budget.has("maxSteps")) {
            return true;
        }
        JsonNode runtimePrompts = root.path("runtimePrompts");
        if (!runtimePrompts.isObject()) {
            return false;
        }
        if (runtimePrompts.has("verify")
                || runtimePrompts.has("finalAnswer")
                || runtimePrompts.has("oneshot")
                || runtimePrompts.has("react")) {
            return true;
        }
        JsonNode planExecute = runtimePrompts.path("planExecute");
        if (!planExecute.isObject()) {
            return false;
        }
        return planExecute.has("executeToolsTitle")
                || planExecute.has("planCallableToolsTitle")
                || planExecute.has("draftInstructionBlock")
                || planExecute.has("generateInstructionBlockFromDraft")
                || planExecute.has("generateInstructionBlockDirect")
                || planExecute.has("taskRequireToolUserPrompt")
                || planExecute.has("taskMultipleToolsUserPrompt")
                || planExecute.has("taskUpdateNoProgressUserPrompt")
                || planExecute.has("taskContinueUserPrompt")
                || planExecute.has("updateRoundPromptTemplate")
                || planExecute.has("updateRoundMultipleToolsUserPrompt")
                || planExecute.has("allStepsCompletedUserPrompt");
    }

    private static boolean hasLegacyPlanExecuteStageFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return hasLegacyStageFields(node.path("plan"))
                || hasLegacyStageFields(node.path("execute"))
                || hasLegacyStageFields(node.path("summary"));
    }

    private static boolean hasLegacyStageFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("providerKey")
                || node.has("model")
                || node.has("reasoning")
                || node.has("tools");
    }

    private static boolean hasLegacyModelConfigFields(JsonNode modelConfig) {
        if (modelConfig == null || !modelConfig.isObject()) {
            return false;
        }
        return modelConfig.has("providerKey") || modelConfig.has("model");
    }
}
