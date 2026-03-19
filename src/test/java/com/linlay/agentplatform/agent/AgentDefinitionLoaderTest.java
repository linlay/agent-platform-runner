package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.mode.ReactMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
import com.linlay.agentplatform.testsupport.TestModelRegistryServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDefinitionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadExternalAgentWithKeyNameIcon() throws IOException {
        writeYaml("ops_daily.yml", """
                key: ops_daily
                name: 运维日报助手
                role: 运维助手
                description: 运维助手
                icon: "emoji:📅"
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _bash_
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: 先规划
                  execute:
                    systemPrompt: 再执行
                  summary:
                    systemPrompt: 最后总结
                """);

        AgentDefinition definition = loadById().get("ops_daily");

        assertThat(definition).isNotNull();
        assertThat(definition.name()).isEqualTo("运维日报助手");
        assertThat(definition.icon()).isEqualTo("emoji:📅");
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
        assertThat(definition.tools()).containsExactlyInAnyOrder("_bash_", "_plan_add_tasks_", "_plan_update_task_");

        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().systemPrompt()).isEqualTo("先规划");
        assertThat(mode.planStage().deepThinking()).isFalse();
        assertThat(mode.executeStage().systemPrompt()).isEqualTo("再执行");
        assertThat(mode.summaryStage().systemPrompt()).isEqualTo("最后总结");
    }

    @Test
    void shouldLoadSandboxConfigEnvironmentId() throws IOException {
        writeYaml("sandboxed.yml", """
                key: sandboxed
                name: Sandboxed Agent
                role: Sandboxed Agent
                description: sandboxed
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - container_hub_bash
                sandboxConfig:
                  environmentId: shell
                mode: ONESHOT
                plain:
                  systemPrompt: use sandbox
                """);

        AgentDefinition definition = loadById().get("sandboxed");

        assertThat(definition).isNotNull();
        assertThat(definition.tools()).contains("container_hub_bash");
        assertThat(definition.sandboxConfig().environmentId()).isEqualTo("shell");
    }

    @Test
    void shouldLoadSandboxConfigWithLevel() throws IOException {
        writeYaml("sandboxed_agent_level.yml", """
                key: sandboxed_agent_level
                name: Sandboxed Agent Level
                role: Sandboxed Agent Level
                description: sandboxed with agent level
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - container_hub_bash
                sandboxConfig:
                  environmentId: shell
                  level: agent
                mode: ONESHOT
                plain:
                  systemPrompt: use sandbox
                """);

        AgentDefinition definition = loadById().get("sandboxed_agent_level");

        assertThat(definition).isNotNull();
        assertThat(definition.sandboxConfig().environmentId()).isEqualTo("shell");
        assertThat(definition.sandboxConfig().level()).isEqualTo(
                com.linlay.agentplatform.agent.runtime.SandboxLevel.AGENT
        );
    }

    @Test
    void shouldLoadActualDemoContainerHubValidatorDefinition() throws IOException {
        Files.copy(
                Path.of("agents", "demoContainerHubValidator.yml"),
                tempDir.resolve("demoContainerHubValidator.yml")
        );

        AgentDefinition definition = loadById().get("demoContainerHubValidator");

        assertThat(definition).isNotNull();
        assertThat(definition.name()).isEqualTo("Atlas");
        assertThat(definition.tools()).containsExactly("container_hub_bash");
        assertThat(definition.skills()).containsExactly("container_hub_validation");
        assertThat(definition.sandboxConfig().environmentId()).isEqualTo("shell");
        assertThat(definition.sandboxConfig().level()).isEqualTo(SandboxLevel.RUN);
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.REACT);

        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.maxSteps()).isEqualTo(6);
        assertThat(mode.stage().systemPrompt()).contains("先做 Bash smoke test");
        assertThat(mode.stage().systemPrompt()).contains("python3");
    }

    @Test
    void shouldLoadExampleDailyOfficeAssistantDefinition() throws IOException {
        Files.copy(
                Path.of("example", "agents", "dailyOfficeAssistant.yml"),
                tempDir.resolve("dailyOfficeAssistant.yml")
        );

        AgentDefinition definition = loadById().get("dailyOfficeAssistant");

        assertThat(definition).isNotNull();
        assertThat(definition.name()).isEqualTo("文澜");
        assertThat(definition.tools()).containsExactly("container_hub_bash");
        assertThat(definition.skills()).containsExactly("docx", "pptx");
        assertThat(definition.sandboxConfig().environmentId()).isEqualTo("daily-office");
        assertThat(definition.sandboxConfig().level()).isEqualTo(SandboxLevel.RUN);
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.REACT);

        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.maxSteps()).isEqualTo(10);
        assertThat(mode.stage().systemPrompt()).contains("container_hub_bash");
        assertThat(mode.stage().systemPrompt()).contains("/tmp");
        assertThat(mode.stage().systemPrompt()).contains("pptxgenjs");
        assertThat(mode.stage().systemPrompt()).contains("/api/data?file=<chatId>%2F<filename>&download=true");
    }

    @Test
    void shouldLoadExternalAgentWithIconObject() throws IOException {
        writeYaml("demo_icon_object.yml", """
                key: demo_icon_object
                name: 图标对象
                role: 图标对象
                description: 对象图标
                icon:
                  name: rocket
                  color: "#3F7BFA"
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: 你好
                """);

        AgentDefinition definition = loadById().get("demo_icon_object");

        assertThat(definition).isNotNull();
        assertThat(definition.icon()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> icon = (Map<String, Object>) definition.icon();
        assertThat(icon).containsEntry("name", "rocket");
        assertThat(icon).containsEntry("color", "#3F7BFA");
    }

    @Test
    void shouldIgnoreAvatarAlias() throws IOException {
        writeYaml("legacy_avatar.yml", """
                key: legacy_avatar
                name: 旧头像字段
                role: 旧头像字段
                description: legacy avatar
                avatar: "emoji:📦"
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        AgentDefinition definition = loadById().get("legacy_avatar");
        assertThat(definition).isNotNull();
        assertThat(definition.icon()).isNull();
    }

    @Test
    void shouldRejectLegacyAgentConfig() throws IOException {
        writeYaml("legacy.yml", """
                key: legacy
                name: Legacy Agent
                role: Legacy Agent
                description: legacy
                providerKey: bailian
                model: qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: 旧模式
                """);

        assertThat(loadById()).doesNotContainKey("legacy");
    }

    @Test
    void shouldRejectModelConfigWithProviderAndModelFields() throws IOException {
        writeYaml("legacy_model_config.yml", """
                key: legacy_model_config
                name: Legacy Model Config
                role: Legacy Model Config
                description: legacy modelConfig
                modelConfig:
                  providerKey: bailian
                  model: qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        assertThat(loadById()).doesNotContainKey("legacy_model_config");
    }

    @Test
    void shouldRejectAgentWhenHeaderOrderIsWrong() throws IOException {
        writeYaml("wrong_header.yml", """
                key: wrong_header
                name: Wrong Header
                description: wrong order
                role: Wrong Header
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        assertThat(loadById()).doesNotContainKey("wrong_header");
    }

    @Test
    void shouldRejectAgentWhenCommentAppearsBeforeHeader() throws IOException {
        writeYaml("comment_before_header.yml", """
                # comment
                key: comment_before_header
                name: Comment Before Header
                role: Comment Before Header
                description: comment before header
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        assertThat(loadById()).doesNotContainKey("comment_before_header");
    }

    @Test
    void shouldRejectAgentWhenDescriptionUsesBlockScalarInHeader() throws IOException {
        writeYaml("multiline_description.yml", """
                key: multiline_description
                name: Multiline Description
                role: Multiline Description
                description: |
                  line 1
                  line 2
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        assertThat(loadById()).doesNotContainKey("multiline_description");
    }

    @Test
    void shouldLoadYamlAgentDefinitionsAcrossModes() throws IOException {
        writeYaml("yaml_oneshot.yml", """
                key: yaml_oneshot
                name: YAML Oneshot
                role: YAML Oneshot
                description: yaml oneshot
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: |
                    你是 YAML 助手
                    请保留多行
                """);
        writeYaml("yaml_react.yaml", """
                key: yaml_react
                name: YAML React
                role: YAML React
                description: yaml react
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: REACT
                react:
                  systemPrompt: react prompt
                  maxSteps: 4
                """);
        writeYaml("yaml_plan_execute.yml", """
                key: yaml_plan_execute
                name: YAML Plan Execute
                role: YAML Plan Execute
                description: yaml plan execute
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: |
                      先规划
                      再拆解
                  execute:
                    systemPrompt: 执行任务
                  summary:
                    systemPrompt: 总结结果
                """);

        Map<String, AgentDefinition> byId = loadById();

        assertThat(byId).containsKeys("yaml_oneshot", "yaml_react", "yaml_plan_execute");
        assertThat(byId.get("yaml_oneshot").mode()).isEqualTo(AgentRuntimeMode.ONESHOT);
        assertThat(byId.get("yaml_oneshot").systemPrompt()).isEqualTo("你是 YAML 助手\n请保留多行");
        assertThat(byId.get("yaml_react").mode()).isEqualTo(AgentRuntimeMode.REACT);
        assertThat(byId.get("yaml_plan_execute").mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);

        PlanExecuteMode mode = (PlanExecuteMode) byId.get("yaml_plan_execute").agentMode();
        assertThat(mode.planStage().systemPrompt()).isEqualTo("先规划\n再拆解");
        assertThat(mode.executeStage().systemPrompt()).isEqualTo("执行任务");
        assertThat(mode.summaryStage().systemPrompt()).isEqualTo("总结结果");
    }

    @Test
    void shouldFailFastOnLegacyJsonFiles() throws IOException {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {
                  "key": "legacy",
                  "name": "legacy",
                  "role": "legacy",
                  "description": "legacy"
                }
                """);

        assertThatThrownBy(this::loadById)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy JSON agent files are no longer supported");
    }

    @Test
    void shouldPreferYmlWhenBasenameConflictsAcrossYamlExtensions() throws IOException {
        writeYaml("conflict_agent.yml", """
                key: conflict_agent
                name: Conflict Agent
                role: Conflict Agent
                description: yml version
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yml prompt
                """);
        writeYaml("conflict_agent.yaml", """
                key: conflict_agent
                name: Conflict Agent
                role: Conflict Agent
                description: yaml version
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yaml prompt
                """);

        AgentDefinition definition = loadById().get("conflict_agent");

        assertThat(definition).isNotNull();
        assertThat(definition.description()).isEqualTo("yml version");
        assertThat(definition.systemPrompt()).isEqualTo("yml prompt");
    }

    @Test
    void shouldParseAllThreeModes() throws IOException {
        writeYaml("m_oneshot.yml", """
                key: m_oneshot
                name: M Oneshot
                role: M Oneshot
                description: oneshot
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig: null
                mode: ONESHOT
                plain:
                  systemPrompt: oneshot prompt
                """);
        writeYaml("m_react.yml", """
                key: m_react
                name: M React
                role: M React
                description: react
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig: null
                mode: REACT
                react:
                  systemPrompt: react prompt
                  maxSteps: 5
                """);
        writeYaml("m_plan_execute.yml", """
                key: m_plan_execute
                name: M Plan Execute
                role: M Plan Execute
                description: plan execute
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig: null
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan prompt
                  execute:
                    systemPrompt: execute prompt
                  summary:
                    systemPrompt: summary prompt
                """);

        Map<String, AgentDefinition> byId = loadById();

        assertThat(byId).hasSize(3);
        assertThat(byId.get("m_oneshot").mode()).isEqualTo(AgentRuntimeMode.ONESHOT);
        assertThat(byId.get("m_react").mode()).isEqualTo(AgentRuntimeMode.REACT);
        assertThat(byId.get("m_plan_execute").mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
    }

    @Test
    void shouldLoadTopLevelSkillConfig() throws IOException {
        writeYaml("skills_top_level.yml", """
                key: skills_top_level
                name: Skills Top Level
                role: Skills Top Level
                description: skills top level
                modelConfig:
                  modelKey: bailian-qwen3-max
                skillConfig:
                  skills:
                    - screenshot
                    - Doc
                    - screenshot
                mode: ONESHOT
                plain:
                  systemPrompt: test prompt
                """);

        AgentDefinition definition = loadById().get("skills_top_level");

        assertThat(definition).isNotNull();
        assertThat(definition.skills()).containsExactly("screenshot", "doc");
    }

    @Test
    void shouldMergeSkillsAliasAndSkillConfig() throws IOException {
        writeYaml("skills_alias.yml", """
                key: skills_alias
                name: Skills Alias
                role: Skills Alias
                description: skills alias
                modelConfig:
                  modelKey: bailian-qwen3-max
                skills:
                  - pdf
                  - doc
                skillConfig:
                  skills:
                    - screenshot
                    - PDF
                mode: ONESHOT
                plain:
                  systemPrompt: test prompt
                """);

        AgentDefinition definition = loadById().get("skills_alias");

        assertThat(definition).isNotNull();
        assertThat(definition.skills()).containsExactly("pdf", "doc", "screenshot");
    }

    @Test
    void shouldLoadSkillMathDemoWithSystemSkillToolName() throws IOException {
        writeYaml("demo_mode_plain_skill_math.yml", """
                key: demoModePlainSkillMath
                name: Demo Mode Plain Skill Math
                role: Demo Mode Plain Skill Math
                description: skill math demo
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _skill_run_script_
                skillConfig:
                  skills:
                    - math_basic
                    - math_stats
                    - text_utils
                mode: ONESHOT
                plain:
                  systemPrompt: test prompt
                """);

        AgentDefinition definition = loadById().get("demoModePlainSkillMath");

        assertThat(definition).isNotNull();
        assertThat(definition.tools()).containsExactly("_skill_run_script_");
        assertThat(definition.skills()).containsExactly("math_basic", "math_stats", "text_utils");
    }

    @Test
    void shouldAllowMissingTopLevelModelConfigWhenStageModelExists() throws IOException {
        writeYaml("inner_model_only.yml", """
                key: inner_model_only
                name: Inner Model Only
                role: Inner Model Only
                description: inner model only
                toolConfig: null
                mode: ONESHOT
                plain:
                  systemPrompt: inner model prompt
                  modelConfig:
                    modelKey: siliconflow-deepseek-v3_2
                """);

        AgentDefinition definition = loadById().get("inner_model_only");

        assertThat(definition).isNotNull();
        assertThat(definition.providerKey()).isEqualTo("siliconflow");
        assertThat(definition.model()).isEqualTo("deepseek-ai/DeepSeek-V3.2");
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.ONESHOT);

        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().providerKey()).isEqualTo("siliconflow");
        assertThat(mode.stage().model()).isEqualTo("deepseek-ai/DeepSeek-V3.2");
    }

    @Test
    void shouldRejectAgentWithoutAnyModelConfig() throws IOException {
        writeYaml("missing_model_config.yml", """
                key: missing_model_config
                name: Missing Model Config
                role: Missing Model Config
                description: missing model config
                toolConfig: null
                mode: ONESHOT
                plain:
                  systemPrompt: plain prompt
                """);

        assertThat(loadById()).doesNotContainKey("missing_model_config");
    }

    @Test
    void shouldInheritStageModelAndForcePlanExecuteRequiredTools() throws IOException {
        writeYaml("inherit_plan.yml", """
                key: inherit_plan
                name: Inherit Plan
                role: Inherit Plan
                description: inherit test
                modelConfig:
                  modelKey: bailian-qwen3-max
                  reasoning:
                    enabled: true
                    effort: HIGH
                toolConfig:
                  backends:
                    - _bash_
                    - datetime
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan stage
                  execute:
                    systemPrompt: execute stage
                    toolConfig: null
                  summary:
                    systemPrompt: summary stage
                """);

        AgentDefinition definition = loadById().get("inherit_plan");

        assertThat(definition).isNotNull();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();

        assertThat(mode.planStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.planStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.planStage().deepThinking()).isFalse();
        assertThat(mode.planStage().tools()).containsExactlyInAnyOrder("_bash_", "datetime", "_plan_add_tasks_");

        assertThat(mode.executeStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.executeStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.executeStage().tools()).containsExactly("_plan_update_task_");

        assertThat(mode.summaryStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.summaryStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.summaryStage().tools()).containsExactlyInAnyOrder("_bash_", "datetime");
        assertThat(definition.tools()).contains("_plan_add_tasks_", "_plan_update_task_");
    }

    @Test
    void shouldParsePlanDeepThinkingFlag() throws IOException {
        writeYaml("deep_thinking_plan.yml", """
                key: deep_thinking_plan
                name: Deep Thinking Plan
                role: Deep Thinking Plan
                description: deep thinking test
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - datetime
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan
                    deepThinking: true
                  execute:
                    systemPrompt: execute
                  summary:
                    systemPrompt: summary
                """);

        AgentDefinition definition = loadById().get("deep_thinking_plan");

        assertThat(definition).isNotNull();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().deepThinking()).isTrue();
        assertThat(mode.executeStage().deepThinking()).isFalse();
        assertThat(mode.summaryStage().deepThinking()).isFalse();
    }

    @Test
    void shouldRejectPlanExecuteWhenExecuteDeepThinkingTrue() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("execute_deep_true.yml", "execute_deep_true", "execute", true);
        assertThat(loadById()).doesNotContainKey("execute_deep_true");
    }

    @Test
    void shouldRejectPlanExecuteWhenExecuteDeepThinkingFalse() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("execute_deep_false.yml", "execute_deep_false", "execute", false);
        assertThat(loadById()).doesNotContainKey("execute_deep_false");
    }

    @Test
    void shouldRejectPlanExecuteWhenSummaryDeepThinkingTrue() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("summary_deep_true.yml", "summary_deep_true", "summary", true);
        assertThat(loadById()).doesNotContainKey("summary_deep_true");
    }

    @Test
    void shouldRejectPlanExecuteWhenSummaryDeepThinkingFalse() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("summary_deep_false.yml", "summary_deep_false", "summary", false);
        assertThat(loadById()).doesNotContainKey("summary_deep_false");
    }

    @Test
    void shouldParseSupportedRuntimePromptsAndFallbackToDefaults() throws IOException {
        writeYaml("runtime_prompts.yml", """
                key: runtime_prompts
                name: Runtime Prompts
                role: Runtime Prompts
                description: runtime prompts
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: plain prompt
                runtimePrompts:
                  planExecute:
                    taskExecutionPromptTemplate: TASK={{task_id}}|{{task_description}}
                  skill:
                    catalogHeader: skills-header-override
                  toolAppendix:
                    toolDescriptionTitle: tool-desc-title-override
                """);

        AgentDefinition definition = loadById().get("runtime_prompts");

        assertThat(definition).isNotNull();
        SkillAppend skillAppend = definition.agentMode().skillAppend();
        ToolAppend toolAppend = definition.agentMode().toolAppend();
        assertThat(skillAppend.catalogHeader()).isEqualTo("skills-header-override");
        assertThat(skillAppend.disclosureHeader()).isEqualTo(SkillAppend.DEFAULTS.disclosureHeader());
        assertThat(toolAppend.toolDescriptionTitle()).isEqualTo("tool-desc-title-override");
        assertThat(toolAppend.afterCallHintTitle()).isEqualTo(ToolAppend.DEFAULTS.afterCallHintTitle());
    }

    @Test
    void shouldIgnoreRemovedVerifyAndRuntimePromptFields() throws IOException {
        writeYaml("removed_fields.yml", """
                key: removed_fields
                name: Removed Fields
                role: Removed Fields
                description: removed fields
                modelConfig:
                  modelKey: bailian-qwen3-max
                verify: NONE
                mode: ONESHOT
                plain:
                  systemPrompt: plain prompt
                runtimePrompts:
                  verify:
                    systemPrompt: legacy
                """);

        AgentDefinition definition = loadById().get("removed_fields");
        assertThat(definition).isNotNull();
        assertThat(definition.id()).isEqualTo("removed_fields");
    }

    @Test
    void shouldParseBudgetV2Config() throws IOException {
        writeYaml("budget_v2.yml", """
                key: budget_v2
                name: Budget V2
                role: Budget V2
                description: budget v2
                modelConfig:
                  modelKey: bailian-qwen3-max
                budget:
                  runTimeoutMs: 180000
                  model:
                    maxCalls: 18
                    timeoutMs: 45000
                    retryCount: 2
                  tool:
                    maxCalls: 36
                    timeoutMs: 90000
                    retryCount: 3
                mode: ONESHOT
                plain:
                  systemPrompt: budget test
                """);

        AgentDefinition definition = loadById().get("budget_v2");

        assertThat(definition).isNotNull();
        assertThat(definition.runSpec().budget().runTimeoutMs()).isEqualTo(180000);
        assertThat(definition.runSpec().budget().model().maxCalls()).isEqualTo(18);
        assertThat(definition.runSpec().budget().model().timeoutMs()).isEqualTo(45000);
        assertThat(definition.runSpec().budget().model().retryCount()).isEqualTo(2);
        assertThat(definition.runSpec().budget().tool().maxCalls()).isEqualTo(36);
        assertThat(definition.runSpec().budget().tool().timeoutMs()).isEqualTo(90000);
        assertThat(definition.runSpec().budget().tool().retryCount()).isEqualTo(3);
    }

    @Test
    void shouldRejectLegacyBudgetFields() throws IOException {
        writeYaml("budget_legacy.yml", """
                key: budget_legacy
                name: Budget Legacy
                role: Budget Legacy
                description: budget legacy
                modelConfig:
                  modelKey: bailian-qwen3-max
                budget:
                  maxModelCalls: 8
                  maxToolCalls: 16
                  timeoutMs: 120000
                  retryCount: 1
                mode: ONESHOT
                plain:
                  systemPrompt: budget legacy test
                """);

        assertThat(loadById()).doesNotContainKey("budget_legacy");
    }

    @Test
    void shouldLoadBothPlanExecuteVariantsWhenKeysAreUnique() throws IOException {
        writeYaml("demoModePlanExecute.yml", """
                key: demoModePlanExecute
                name: Demo Mode Plan Execute
                role: Demo Mode Plan Execute
                description: main demo
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan
                  execute:
                    systemPrompt: execute
                  summary:
                    systemPrompt: summary
                """);
        writeYaml("demoModePlanExecuteDeepThinking.yml", """
                key: demoModePlanExecuteDeepThinking
                name: Demo Mode Plan Execute Deep Thinking
                role: Demo Mode Plan Execute Deep Thinking
                description: deep demo
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan
                    deepThinking: true
                  execute:
                    systemPrompt: execute
                  summary:
                    systemPrompt: summary
                """);

        Map<String, AgentDefinition> byId = loadById();

        assertThat(byId).containsKeys("demoModePlanExecute", "demoModePlanExecuteDeepThinking");
        assertThat(byId.get("demoModePlanExecute").mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
        assertThat(byId.get("demoModePlanExecuteDeepThinking").mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
    }

    private Map<String, AgentDefinition> loadById() {
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);
        return loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));
    }

    private AgentDefinitionLoader newLoader(AgentProperties properties) {
        return new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
    }

    private void writeYaml(String fileName, String content) throws IOException {
        Files.writeString(tempDir.resolve(fileName), content);
    }

    private void writePlanExecuteWithDisallowedDeepThinking(
            String fileName,
            String key,
            String stage,
            boolean deepThinking
    ) throws IOException {
        String deepThinkingSection = deepThinking ? "true" : "false";
        writeYaml(fileName, """
                key: %s
                name: %s
                role: %s
                description: invalid deepThinking stage config
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan
                    deepThinking: true
                  execute:
                    systemPrompt: execute%s
                  summary:
                    systemPrompt: summary%s
                """.formatted(
                key,
                key,
                key,
                "execute".equals(stage) ? "\n    deepThinking: " + deepThinkingSection : "",
                "summary".equals(stage) ? "\n    deepThinking: " + deepThinkingSection : ""
        ));
    }
}
