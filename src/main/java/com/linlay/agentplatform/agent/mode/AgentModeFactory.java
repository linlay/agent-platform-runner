package com.linlay.agentplatform.agent.mode;

import com.linlay.agentplatform.agent.AgentConfigFile;
import com.linlay.agentplatform.agent.AgentPromptFiles;
import com.linlay.agentplatform.agent.config.AgentModelConfig;
import com.linlay.agentplatform.agent.config.AgentRuntimePromptsConfig;
import com.linlay.agentplatform.agent.config.AgentToolConfig;
import com.linlay.agentplatform.agent.PlanToolConstants;
import com.linlay.agentplatform.agent.SkillAppend;
import com.linlay.agentplatform.agent.ToolAppend;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.util.StringHelpers;
import com.linlay.agentplatform.model.ModelDefinition;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class AgentModeFactory {

    private static final String DEFAULT_SKILL_TOOL = "_sandbox_bash_";
    private static final List<String> DEFAULT_MEMORY_TOOLS = List.of("_memory_write_", "_memory_read_", "_memory_search_");

    private AgentModeFactory() {
    }

    public static AgentMode create(
            AgentRuntimeMode mode,
            AgentConfigFile config,
            Path file,
            AgentPromptFiles promptFiles,
            Function<String, ModelDefinition> modelResolver,
            boolean memoryToolsEnabled,
            AgentDefaultsProperties defaults
    ) {
        AgentRuntimePromptsConfig runtimePromptsConfig = config == null ? null : config.getRuntimePrompts();
        SkillAppend skillAppend = buildSkillAppend(runtimePromptsConfig);
        ToolAppend toolAppend = buildToolAppend(runtimePromptsConfig);
        String taskExecutionPromptTemplate = buildTaskExecutionPromptTemplate(runtimePromptsConfig);
        Budget defaultBudget = defaults == null ? Budget.DEFAULT : defaults.defaultBudget();
        int defaultReactMaxSteps = defaults == null ? 60 : defaults.defaultReactMaxSteps();
        int defaultPlanExecuteMaxSteps = defaults == null ? 60 : defaults.defaultPlanExecuteMaxSteps();

        return switch (mode) {
            case ONESHOT -> {
                StageSettings stage = stageSettings(
                        config,
                        config == null ? null : config.getPlain(),
                        List.of(),
                        promptFiles == null ? null : promptFiles.plainStageContent(),
                        modelResolver,
                        memoryToolsEnabled
                );
                if (isBlank(stage.primaryPrompt())) {
                    throw new IllegalArgumentException("AGENTS.md is required for ONESHOT agents: " + file);
                }
                yield new OneshotMode(stage, skillAppend, toolAppend, defaultBudget);
            }
            case REACT -> {
                AgentConfigFile.ReactConfig react = config == null ? null : config.getReact();
                StageSettings stage = stageSettings(
                        config,
                        react,
                        List.of(),
                        promptFiles == null ? null : promptFiles.reactStageContent(),
                        modelResolver,
                        memoryToolsEnabled
                );
                if (isBlank(stage.primaryPrompt())) {
                    throw new IllegalArgumentException("AGENTS.md is required for REACT agents: " + file);
                }
                int maxSteps = react != null && react.getMaxSteps() != null ? react.getMaxSteps() : defaultReactMaxSteps;
                yield new ReactMode(stage, maxSteps, skillAppend, toolAppend, defaultBudget);
            }
            case PLAN_EXECUTE -> {
                AgentConfigFile.PlanExecuteConfig pe = config == null ? null : config.getPlanExecute();
                validatePlanExecuteDeepThinking(pe == null ? null : pe.getExecute(), "planExecute.execute.deepThinking", file);
                validatePlanExecuteDeepThinking(pe == null ? null : pe.getSummary(), "planExecute.summary.deepThinking", file);
                StageSettings planStage = stageSettings(
                        config,
                        pe == null ? null : pe.getPlan(),
                        List.of(PlanToolConstants.PLAN_ADD_TASKS_TOOL),
                        promptFiles == null ? null : promptFiles.planStageContent(),
                        modelResolver,
                        memoryToolsEnabled
                );
                StageSettings executeStage = stageSettings(
                        config,
                        pe == null ? null : pe.getExecute(),
                        List.of(PlanToolConstants.PLAN_UPDATE_TASK_TOOL),
                        promptFiles == null ? null : promptFiles.executeStageContent(),
                        modelResolver,
                        memoryToolsEnabled
                );
                StageSettings summaryStage = stageSettings(
                        config,
                        pe == null ? null : pe.getSummary(),
                        List.of(),
                        promptFiles == null ? null : promptFiles.summaryStageContent(),
                        modelResolver,
                        memoryToolsEnabled
                );
                if (isBlank(planStage.primaryPrompt())
                        || isBlank(executeStage.primaryPrompt())
                        || isBlank(summaryStage.primaryPrompt())) {
                    throw new IllegalArgumentException(
                            "planExecute.plan.promptFile, planExecute.execute.promptFile, and planExecute.summary.promptFile are required: " + file);
                }
                int maxSteps = pe != null && pe.getMaxSteps() != null && pe.getMaxSteps() > 0
                        ? pe.getMaxSteps()
                        : defaultPlanExecuteMaxSteps;
                yield new PlanExecuteMode(
                        planStage,
                        executeStage,
                        summaryStage,
                        skillAppend,
                        toolAppend,
                        defaultBudget,
                        taskExecutionPromptTemplate,
                        maxSteps
                );
            }
        };
    }

    private static SkillAppend buildSkillAppend(AgentRuntimePromptsConfig config) {
        if (config == null) {
            return SkillAppend.DEFAULTS;
        }
        AgentRuntimePromptsConfig.SkillPromptConfig skillConfig = config.getSkill();
        if (skillConfig == null) {
            return SkillAppend.DEFAULTS;
        }
        return new SkillAppend(
                pick(skillConfig.getCatalogHeader(), SkillAppend.DEFAULTS.catalogHeader()),
                pick(skillConfig.getDisclosureHeader(), SkillAppend.DEFAULTS.disclosureHeader()),
                pick(skillConfig.getInstructionsLabel(), SkillAppend.DEFAULTS.instructionsLabel())
        );
    }

    private static ToolAppend buildToolAppend(AgentRuntimePromptsConfig config) {
        if (config == null) {
            return ToolAppend.DEFAULTS;
        }
        AgentRuntimePromptsConfig.ToolAppendixPromptConfig toolAppendixConfig = config.getToolAppendix();
        if (toolAppendixConfig == null) {
            return ToolAppend.DEFAULTS;
        }
        return new ToolAppend(
                pick(toolAppendixConfig.getToolDescriptionTitle(), ToolAppend.DEFAULTS.toolDescriptionTitle()),
                pick(toolAppendixConfig.getAfterCallHintTitle(), ToolAppend.DEFAULTS.afterCallHintTitle())
        );
    }

    private static String buildTaskExecutionPromptTemplate(AgentRuntimePromptsConfig config) {
        if (config == null) {
            return null;
        }
        AgentRuntimePromptsConfig.PlanExecutePromptConfig peConfig = config.getPlanExecute();
        if (peConfig == null) {
            return null;
        }
        String template = peConfig.getTaskExecutionPromptTemplate();
        return StringUtils.hasText(template) ? template.trim() : null;
    }

    private static String pick(String configured, String fallback) {
        return StringUtils.hasText(configured) ? configured.trim() : fallback;
    }

    private static void validatePlanExecuteDeepThinking(
            AgentConfigFile.StageConfig stage,
            String fieldPath,
            Path file
    ) {
        if (stage != null && stage.isDeepThinkingProvided()) {
            throw new IllegalArgumentException(fieldPath + " is not allowed: " + file);
        }
    }

    private static StageSettings stageSettings(
            AgentConfigFile config,
            AgentConfigFile.StageConfig stage,
            List<String> requiredTools,
            String instructionsPrompt,
            Function<String, ModelDefinition> modelResolver,
            boolean memoryToolsEnabled
    ) {
        AgentModelConfig resolvedModelConfig = resolveModelConfig(config, stage);
        if (resolvedModelConfig == null || !StringUtils.hasText(resolvedModelConfig.getModelKey())) {
            throw new IllegalArgumentException("modelConfig.modelKey is required");
        }
        if (modelResolver == null) {
            throw new IllegalArgumentException("modelResolver is required");
        }
        String modelKey = normalize(resolvedModelConfig.getModelKey());
        ModelDefinition resolvedModel = modelResolver.apply(modelKey);
        if (resolvedModel == null) {
            throw new IllegalArgumentException("Unknown modelKey: " + modelKey);
        }
        AgentConfigFile.ReasoningConfig resolvedReasoning = resolvedModelConfig == null ? null : resolvedModelConfig.getReasoning();
        boolean reasoningEnabled = resolvedReasoning != null && Boolean.TRUE.equals(resolvedReasoning.getEnabled());
        ComputePolicy reasoningEffort = resolvedReasoning != null && resolvedReasoning.getEffort() != null
                ? resolvedReasoning.getEffort()
                : ComputePolicy.MEDIUM;
        List<String> tools = resolveTools(config, stage, requiredTools, memoryToolsEnabled);

        return new StageSettings(
                normalize(stage == null ? null : stage.getSystemPrompt()),
                normalize(resolvedModel.key()),
                normalize(resolvedModel.provider()),
                normalize(resolvedModel.modelId()),
                resolvedModel.protocol(),
                tools,
                reasoningEnabled,
                reasoningEffort,
                stage != null && stage.isDeepThinking(),
                instructionsPrompt
        );
    }

    private static AgentModelConfig resolveModelConfig(AgentConfigFile config, AgentConfigFile.StageConfig stage) {
        AgentModelConfig top = config == null ? null : config.getModelConfig();
        if (stage == null || !stage.isModelConfigProvided() || stage.getModelConfig() == null) {
            return top;
        }
        return stage.getModelConfig();
    }

    private static List<String> resolveTools(
            AgentConfigFile config,
            AgentConfigFile.StageConfig stage,
            List<String> requiredTools,
            boolean memoryToolsEnabled
    ) {
        AgentToolConfig top = config == null ? null : config.getToolConfig();
        List<String> resolved;
        if (stage == null || !stage.isToolConfigProvided()) {
            resolved = normalizeTools(top);
            return addImplicitMemoryTools(config, addImplicitSkillTools(config, mergeRequiredTools(resolved, requiredTools)), memoryToolsEnabled);
        }
        if (stage.isToolConfigExplicitNull()) {
            resolved = List.of();
            return addImplicitMemoryTools(config, addImplicitSkillTools(config, mergeRequiredTools(resolved, requiredTools)), memoryToolsEnabled);
        }
        resolved = normalizeTools(stage.getToolConfig());
        return addImplicitMemoryTools(config, addImplicitSkillTools(config, mergeRequiredTools(resolved, requiredTools)), memoryToolsEnabled);
    }

    private static List<String> normalizeTools(AgentToolConfig toolConfig) {
        if (toolConfig == null) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        addTools(tools, toolConfig.getBackends());
        addTools(tools, toolConfig.getFrontends());
        addTools(tools, toolConfig.getActions());
        return tools.stream().distinct().toList();
    }

    private static List<String> mergeRequiredTools(List<String> tools, List<String> requiredTools) {
        if (requiredTools == null || requiredTools.isEmpty()) {
            return tools == null ? List.of() : List.copyOf(tools);
        }
        List<String> merged = new ArrayList<>(tools == null ? List.of() : tools);
        addTools(merged, requiredTools);
        return merged.stream().distinct().toList();
    }

    private static List<String> addImplicitSkillTools(AgentConfigFile config, List<String> tools) {
        if (!hasDeclaredSkills(config)) {
            return tools == null ? List.of() : List.copyOf(tools);
        }
        List<String> merged = new ArrayList<>(tools == null ? List.of() : tools);
        merged.add(DEFAULT_SKILL_TOOL);
        return merged.stream().distinct().toList();
    }

    private static boolean hasDeclaredSkills(AgentConfigFile config) {
        return config != null
                && config.getSkillConfig() != null
                && config.getSkillConfig().getSkills() != null
                && config.getSkillConfig().getSkills().stream()
                .map(StringHelpers::nullable)
                .anyMatch(value -> value != null && !value.isBlank());
    }

    private static List<String> addImplicitMemoryTools(AgentConfigFile config, List<String> tools, boolean memoryToolsEnabled) {
        if (!memoryToolsEnabled || !isAgentMemoryEnabled(config)) {
            return tools == null ? List.of() : List.copyOf(tools);
        }
        List<String> merged = new ArrayList<>(tools == null ? List.of() : tools);
        merged.addAll(DEFAULT_MEMORY_TOOLS);
        return merged.stream().distinct().toList();
    }

    private static boolean isAgentMemoryEnabled(AgentConfigFile config) {
        return config != null
                && config.getMemoryConfig() != null
                && Boolean.TRUE.equals(config.getMemoryConfig().getEnabled());
    }

    private static void addTools(List<String> tools, List<String> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            return;
        }
        for (String raw : rawTools) {
            String normalized = normalize(raw);
            if (isBlank(normalized)) {
                continue;
            }
            tools.add(normalized.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return StringHelpers.nullable(value);
    }
}
