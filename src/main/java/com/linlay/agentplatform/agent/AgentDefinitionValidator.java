package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public final class AgentDefinitionValidator {

    private static final Set<String> LEGACY_ROOT_FIELDS = Set.of(
            "deepThink",
            "systemPrompt",
            "providerKey",
            "model",
            "reasoning",
            "tools"
    );
    private static final Set<String> LEGACY_STAGE_FIELDS = Set.of(
            "providerKey",
            "model",
            "reasoning",
            "tools"
    );
    private static final Set<String> LEGACY_MODEL_CONFIG_FIELDS = Set.of(
            "providerKey",
            "model"
    );
    private static final Set<String> REMOVED_ROOT_FIELDS = Set.of(
            "avatar",
            "verify",
            "output",
            "toolPolicy"
    );
    private static final Set<String> REMOVED_RUNTIME_PROMPT_FIELDS = Set.of(
            "verify",
            "finalAnswer",
            "oneshot",
            "react"
    );
    private static final Set<String> REMOVED_PLAN_EXECUTE_RUNTIME_PROMPT_FIELDS = Set.of(
            "executeToolsTitle",
            "planCallableToolsTitle",
            "draftInstructionBlock",
            "generateInstructionBlockFromDraft",
            "generateInstructionBlockDirect",
            "taskRequireToolUserPrompt",
            "taskMultipleToolsUserPrompt",
            "taskUpdateNoProgressUserPrompt",
            "taskContinueUserPrompt",
            "updateRoundPromptTemplate",
            "updateRoundMultipleToolsUserPrompt",
            "allStepsCompletedUserPrompt"
    );

    private AgentDefinitionValidator() {
    }

    public static boolean isLegacyConfig(JsonNode root) {
        if (root == null || !root.isObject()) {
            return true;
        }
        if (hasAnyField(root, LEGACY_ROOT_FIELDS)) {
            return true;
        }
        return hasAnyField(root.path("plain"), LEGACY_STAGE_FIELDS)
                || hasAnyField(root.path("react"), LEGACY_STAGE_FIELDS)
                || hasAnyField(root.path("planExecute").path("plan"), LEGACY_STAGE_FIELDS)
                || hasAnyField(root.path("planExecute").path("execute"), LEGACY_STAGE_FIELDS)
                || hasAnyField(root.path("planExecute").path("summary"), LEGACY_STAGE_FIELDS);
    }

    public static boolean hasRemovedFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (hasAnyField(root, REMOVED_ROOT_FIELDS)) {
            return true;
        }
        if (hasAnyField(root.path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)
                || hasAnyField(root.path("plain").path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)
                || hasAnyField(root.path("react").path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)
                || hasAnyField(root.path("planExecute").path("plan").path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)
                || hasAnyField(root.path("planExecute").path("execute").path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)
                || hasAnyField(root.path("planExecute").path("summary").path("modelConfig"), LEGACY_MODEL_CONFIG_FIELDS)) {
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
        if (hasAnyField(runtimePrompts, REMOVED_RUNTIME_PROMPT_FIELDS)) {
            return true;
        }
        return hasAnyField(runtimePrompts.path("planExecute"), REMOVED_PLAN_EXECUTE_RUNTIME_PROMPT_FIELDS);
    }

    private static boolean hasAnyField(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String field : fields) {
            if (node.has(field)) {
                return true;
            }
        }
        return false;
    }
}
