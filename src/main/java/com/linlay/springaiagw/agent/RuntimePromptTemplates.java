package com.linlay.springaiagw.agent;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimePromptTemplates {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-z0-9_]+)\\s*}}");
    private static final RuntimePromptTemplates DEFAULTS = new RuntimePromptTemplates(
            new PlanExecute(
                    """
                            这是任务列表：
                            {{task_list}}
                            当前要执行的 taskId: {{task_id}}
                            当前任务描述: {{task_description}}
                            执行规则:
                            1) 每个执行回合最多调用一个工具；
                            2) 你可按需调用任意可用工具做准备；
                            3) 结束该任务前必须调用 _plan_update_task_ 更新状态。
                            """
            ),
            new Skill(
                    "可用 skills（目录摘要，按需使用，不要虚构不存在的 skill 或脚本）:",
                    "以下是你刚刚调用到的 skill 完整说明（仅本轮补充，不要忽略）:",
                    "instructions"
            ),
            new ToolAppendix(
                    "工具说明:",
                    "工具调用后推荐指令:"
            )
    );

    private final PlanExecute planExecute;
    private final Skill skill;
    private final ToolAppendix toolAppendix;

    public RuntimePromptTemplates(
            PlanExecute planExecute,
            Skill skill,
            ToolAppendix toolAppendix
    ) {
        this.planExecute = planExecute;
        this.skill = skill;
        this.toolAppendix = toolAppendix;
    }

    public static RuntimePromptTemplates defaults() {
        return DEFAULTS;
    }

    public static RuntimePromptTemplates fromConfig(AgentConfigFile.RuntimePromptsConfig config) {
        if (config == null) {
            return defaults();
        }
        RuntimePromptTemplates defaults = defaults();
        AgentConfigFile.PlanExecutePromptConfig planExecuteConfig = config.getPlanExecute();
        AgentConfigFile.SkillPromptConfig skillConfig = config.getSkill();
        AgentConfigFile.ToolAppendixPromptConfig toolAppendixConfig = config.getToolAppendix();
        return new RuntimePromptTemplates(
                new PlanExecute(
                        pick(planExecuteConfig == null ? null : planExecuteConfig.getTaskExecutionPromptTemplate(),
                                defaults.planExecute.taskExecutionPromptTemplate())
                ),
                new Skill(
                        pick(skillConfig == null ? null : skillConfig.getCatalogHeader(), defaults.skill.catalogHeader()),
                        pick(skillConfig == null ? null : skillConfig.getDisclosureHeader(), defaults.skill.disclosureHeader()),
                        pick(skillConfig == null ? null : skillConfig.getInstructionsLabel(), defaults.skill.instructionsLabel())
                ),
                new ToolAppendix(
                        pick(toolAppendixConfig == null ? null : toolAppendixConfig.getToolDescriptionTitle(),
                                defaults.toolAppendix.toolDescriptionTitle()),
                        pick(toolAppendixConfig == null ? null : toolAppendixConfig.getAfterCallHintTitle(),
                                defaults.toolAppendix.afterCallHintTitle())
                )
        );
    }

    public PlanExecute planExecute() {
        return planExecute;
    }

    public Skill skill() {
        return skill;
    }

    public ToolAppendix toolAppendix() {
        return toolAppendix;
    }

    public String render(String template, Map<String, String> values) {
        String source = template == null ? "" : template;
        if (values == null || values.isEmpty()) {
            return source;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(source);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!values.containsKey(key)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String replacement = values.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String pick(String configured, String fallback) {
        return StringUtils.hasText(configured) ? configured.trim() : fallback;
    }

    public record PlanExecute(
            String taskExecutionPromptTemplate
    ) {
    }

    public record Skill(
            String catalogHeader,
            String disclosureHeader,
            String instructionsLabel
    ) {
    }

    public record ToolAppendix(
            String toolDescriptionTitle,
            String afterCallHintTitle
    ) {
    }
}
