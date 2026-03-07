package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.testsupport.TestModelRegistryServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadExternalAgentWithKeyNameIcon() throws IOException {
        Files.writeString(tempDir.resolve("ops_daily.json"), """
                {
                  "key": "ops_daily",
                  "name": "运维日报助手",
                  "icon": "emoji:📅",
                  "description": "运维助手",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": {
                    "backends": ["_bash_"],
                    "frontends": [],
                    "actions": []
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "先规划" },
                    "execute": { "systemPrompt": "再执行" },
                    "summary": { "systemPrompt": "最后总结" }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).containsKey("ops_daily");
        AgentDefinition definition = byId.get("ops_daily");
        assertThat(definition.name()).isEqualTo("运维日报助手");
        assertThat(definition.icon()).isEqualTo("emoji:📅");
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
        assertThat(definition.tools()).containsExactlyInAnyOrder("_bash_", "_plan_add_tasks_", "_plan_update_task_");
        assertThat(definition.agentMode()).isInstanceOf(PlanExecuteMode.class);

        PlanExecuteMode peMode = (PlanExecuteMode) definition.agentMode();
        assertThat(peMode.planStage().systemPrompt()).isEqualTo("先规划");
        assertThat(peMode.planStage().deepThinking()).isFalse();
        assertThat(peMode.executeStage().systemPrompt()).isEqualTo("再执行");
        assertThat(peMode.summaryStage().systemPrompt()).isEqualTo("最后总结");
    }

    @Test
    void shouldLoadExternalAgentWithIconObject() throws IOException {
        Files.writeString(tempDir.resolve("demo_icon_object.json"), """
                {
                  "key": "demo_icon_object",
                  "name": "图标对象",
                  "icon": {
                    "name": "rocket",
                    "color": "#3F7BFA"
                  },
                  "description": "对象图标",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "mode": "ONESHOT",
                  "plain": {
                    "systemPrompt": "你好"
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).containsKey("demo_icon_object");
        AgentDefinition definition = byId.get("demo_icon_object");
        assertThat(definition.icon()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> icon = (Map<String, Object>) definition.icon();
        assertThat(icon).containsEntry("name", "rocket");
        assertThat(icon).containsEntry("color", "#3F7BFA");
    }

    @Test
    void shouldRejectLegacyAgentConfig() throws IOException {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {
                  "description":"legacy",
                  "providerKey":"bailian",
                  "model":"qwen3-max",
                  "mode":"ONESHOT",
                  "plain":{"systemPrompt":"旧模式"}
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);
        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).doesNotContainKey("legacy");
    }

    @Test
    void shouldRejectModelConfigWithProviderAndModelFields() throws IOException {
        Files.writeString(tempDir.resolve("legacy_model_config.json"), """
                {
                  "key": "legacy_model_config",
                  "description": "legacy modelConfig",
                  "modelConfig": {
                    "providerKey": "bailian",
                    "model": "qwen3-max"
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "test" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);
        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).doesNotContainKey("legacy_model_config");
    }

    @Test
    void shouldLoadTripleQuotedPromptForOneshot() throws IOException {
        Files.writeString(tempDir.resolve("fortune_teller.json"), "{" + "\n"
                + "  \"key\": \"fortune_teller\",\n"
                + "  \"description\": \"算命大师\",\n"
                + "  \"modelConfig\": {\n"
                + "    \"modelKey\": \"bailian-qwen3-max\"\n"
                + "  },\n"
                + "  \"toolConfig\": null,\n"
                + "  \"mode\": \"ONESHOT\",\n"
                + "  \"plain\": {\n"
                + "    \"systemPrompt\": \"\"\"\n"
                + "你是算命大师\n"
                + "请先问出生日期\n"
                + "\"\"\"\n"
                + "  }\n"
                + "}\n");

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).containsKey("fortune_teller");
        assertThat(byId.get("fortune_teller").systemPrompt()).isEqualTo("你是算命大师\n请先问出生日期");
        assertThat(byId.get("fortune_teller").mode()).isEqualTo(AgentRuntimeMode.ONESHOT);
    }

    @Test
    void shouldLoadPlanExecuteWithCommentsAndTripleQuotedPrompts() throws IOException {
        Files.writeString(tempDir.resolve("demoModePlanExecute.json"), "{" + "\n"
                + "  \"key\": \"demoModePlanExecute\",\n"
                + "  \"description\": \"plan execute with comments\",\n"
                + "  \"modelConfig\": {\n"
                + "    \"modelKey\": \"bailian-qwen3-max\"\n"
                + "  },\n"
                + "  \"mode\": \"PLAN_EXECUTE\",\n"
                + "  \"planExecute\": {\n"
                + "    \"plan\": {\n"
                + "      // \"deepThinking\": true,\n"
                + "      \"systemPrompt\": \"\"\"\n"
                + "你是高级规划助手。\n"
                + "先规划任务。\n"
                + "\"\"\"\n"
                + "    },\n"
                + "    \"execute\": {\n"
                + "      /* 执行阶段系统提示 */\n"
                + "      \"systemPrompt\": \"\"\"\n"
                + "你是执行助手。\n"
                + "根据taskId执行任务。\n"
                + "\"\"\"\n"
                + "    },\n"
                + "    \"summary\": {\n"
                + "      \"systemPrompt\": \"总结\"\n"
                + "    }\n"
                + "  }\n"
                + "}\n");

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "demoModePlanExecute".equals(item.id()))
                .findFirst()
                .orElseThrow();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();

        assertThat(mode.planStage().systemPrompt()).isEqualTo("你是高级规划助手。\n先规划任务。");
        assertThat(mode.executeStage().systemPrompt()).isEqualTo("你是执行助手。\n根据taskId执行任务。");
        assertThat(mode.summaryStage().systemPrompt()).isEqualTo("总结");
    }

    @Test
    void shouldLoadYamlAgentDefinitionsAcrossModes() throws IOException {
        Files.writeString(tempDir.resolve("yaml_oneshot.yml"), """
                key: yaml_oneshot
                name: YAML Oneshot
                description: yaml oneshot
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: |
                    你是 YAML 助手
                    请保留多行
                """);
        Files.writeString(tempDir.resolve("yaml_react.yaml"), """
                key: yaml_react
                description: yaml react
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: REACT
                react:
                  systemPrompt: react prompt
                  maxSteps: 4
                """);
        Files.writeString(tempDir.resolve("yaml_plan_execute.yml"), """
                key: yaml_plan_execute
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

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, null);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

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
    void shouldUseFileBasenameAcrossJsonAndYamlWhenKeyMissing() throws IOException {
        Files.writeString(tempDir.resolve("basename_json.json"), """
                {
                  "description": "json fallback",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "json prompt" }
                }
                """);
        Files.writeString(tempDir.resolve("basename_yml.yml"), """
                description: yml fallback
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yml prompt
                """);
        Files.writeString(tempDir.resolve("basename_yaml.yaml"), """
                description: yaml fallback
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yaml prompt
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, null);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).containsKeys("basename_json", "basename_yml", "basename_yaml");
    }

    @Test
    void shouldPreferYamlWhenBasenameConflictsAcrossFormats() throws IOException {
        Files.writeString(tempDir.resolve("conflict_agent.json"), """
                {
                  "key": "conflict_agent",
                  "description": "json version",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "json prompt" }
                }
                """);
        Files.writeString(tempDir.resolve("conflict_agent.yml"), """
                key: conflict_agent
                description: yaml version
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yaml prompt
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, null);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "conflict_agent".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.description()).isEqualTo("yaml version");
        assertThat(definition.systemPrompt()).isEqualTo("yaml prompt");
    }

    @Test
    void shouldPreferYamlWhenKeyDuplicatedAcrossFormats() throws IOException {
        Files.writeString(tempDir.resolve("alpha_duplicate.json"), """
                {
                  "key": "dup_agent",
                  "description": "json duplicate",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "json duplicate" }
                }
                """);
        Files.writeString(tempDir.resolve("beta_duplicate.yml"), """
                key: dup_agent
                description: yaml duplicate
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: yaml duplicate
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, null);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "dup_agent".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.description()).isEqualTo("yaml duplicate");
        assertThat(definition.systemPrompt()).isEqualTo("yaml duplicate");
    }

    @Test
    void shouldParseAllThreeModes() throws IOException {
        Files.writeString(tempDir.resolve("m_oneshot.json"), """
                {
                  "key": "m_oneshot",
                  "description": "oneshot",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": null,
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "oneshot prompt" }
                }
                """);
        Files.writeString(tempDir.resolve("m_react.json"), """
                {
                  "key": "m_react",
                  "description": "react",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": null,
                  "mode": "REACT",
                  "react": {
                    "systemPrompt": "react prompt",
                    "maxSteps": 5
                  }
                }
                """);
        Files.writeString(tempDir.resolve("m_plan_execute.json"), """
                {
                  "key": "m_plan_execute",
                  "description": "plan execute",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": null,
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan prompt" },
                    "execute": { "systemPrompt": "execute prompt" },
                    "summary": { "systemPrompt": "summary prompt" }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);
        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).hasSize(3);
        assertThat(byId.get("m_oneshot").mode()).isEqualTo(AgentRuntimeMode.ONESHOT);
        assertThat(byId.get("m_react").mode()).isEqualTo(AgentRuntimeMode.REACT);
        assertThat(byId.get("m_plan_execute").mode()).isEqualTo(AgentRuntimeMode.PLAN_EXECUTE);
    }

    @Test
    void shouldLoadTopLevelSkillConfig() throws IOException {
        Files.writeString(tempDir.resolve("skills_top_level.json"), """
                {
                  "key": "skills_top_level",
                  "description": "skills top level",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "skillConfig": {
                    "skills": ["screenshot", "Doc", "screenshot"]
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "test prompt" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "skills_top_level".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.skills()).containsExactly("screenshot", "doc");
    }

    @Test
    void shouldMergeSkillsAliasAndSkillConfig() throws IOException {
        Files.writeString(tempDir.resolve("skills_alias.json"), """
                {
                  "key": "skills_alias",
                  "description": "skills alias",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "skills": ["pdf", "doc"],
                  "skillConfig": {
                    "skills": ["screenshot", "PDF"]
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "test prompt" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "skills_alias".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.skills()).containsExactly("pdf", "doc", "screenshot");
    }

    @Test
    void shouldLoadSkillMathDemoWithSystemSkillToolName() throws IOException {
        Files.writeString(tempDir.resolve("demo_mode_plain_skill_math.json"), """
                {
                  "key": "demoModePlainSkillMath",
                  "description": "skill math demo",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": {
                    "backends": ["_skill_run_script_"],
                    "frontends": [],
                    "actions": []
                  },
                  "skillConfig": {
                    "skills": ["math_basic", "math_stats", "text_utils"]
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "test prompt" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "demoModePlainSkillMath".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.tools()).containsExactly("_skill_run_script_");
        assertThat(definition.skills()).containsExactly("math_basic", "math_stats", "text_utils");
    }

    @Test
    void shouldAllowMissingTopLevelModelConfigWhenStageModelExists() throws IOException {
        Files.writeString(tempDir.resolve("inner_model_only.json"), """
                {
                  "key": "inner_model_only",
                  "description": "inner model only",
                  "toolConfig": null,
                  "mode": "ONESHOT",
                  "plain": {
                    "systemPrompt": "inner model prompt",
                    "modelConfig": {
                    "modelKey": "siliconflow-deepseek-v3_2"
                  }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "inner_model_only".equals(item.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.providerKey()).isEqualTo("siliconflow");
        assertThat(definition.model()).isEqualTo("deepseek-ai/DeepSeek-V3.2");
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.ONESHOT);

        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().providerKey()).isEqualTo("siliconflow");
        assertThat(mode.stage().model()).isEqualTo("deepseek-ai/DeepSeek-V3.2");
    }

    @Test
    void shouldRejectAgentWithoutAnyModelConfig() throws IOException {
        Files.writeString(tempDir.resolve("missing_model_config.json"), """
                {
                  "key": "missing_model_config",
                  "description": "missing model config",
                  "toolConfig": null,
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "plain prompt" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);
        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).doesNotContainKey("missing_model_config");
    }

    @Test
    void shouldInheritStageModelAndForcePlanExecuteRequiredTools() throws IOException {
        Files.writeString(tempDir.resolve("inherit_plan.json"), """
                {
                  "key": "inherit_plan",
                  "description": "inherit test",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max",
                    "reasoning": { "enabled": true, "effort": "HIGH" }
                  },
                  "toolConfig": {
                    "backends": ["_bash_", "city_datetime"],
                    "frontends": [],
                    "actions": []
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan stage" },
                    "execute": { "systemPrompt": "execute stage", "toolConfig": null },
                    "summary": { "systemPrompt": "summary stage" }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "inherit_plan".equals(item.id()))
                .findFirst()
                .orElseThrow();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();

        assertThat(mode.planStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.planStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.planStage().deepThinking()).isFalse();
        assertThat(mode.planStage().tools()).containsExactlyInAnyOrder("_bash_", "city_datetime", "_plan_add_tasks_");

        assertThat(mode.executeStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.executeStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.executeStage().tools()).containsExactly("_plan_update_task_");

        assertThat(mode.summaryStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.summaryStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.summaryStage().tools()).containsExactlyInAnyOrder("_bash_", "city_datetime");
        assertThat(definition.tools()).contains("_plan_add_tasks_", "_plan_update_task_");
    }

    @Test
    void shouldParsePlanDeepThinkingFlag() throws IOException {
        Files.writeString(tempDir.resolve("deep_thinking_plan.json"), """
                {
                  "key": "deep_thinking_plan",
                  "description": "deep thinking test",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": {
                    "backends": ["city_datetime"],
                    "frontends": [],
                    "actions": []
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan", "deepThinking": true },
                    "execute": { "systemPrompt": "execute" },
                    "summary": { "systemPrompt": "summary" }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "deep_thinking_plan".equals(item.id()))
                .findFirst()
                .orElseThrow();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();

        assertThat(mode.planStage().deepThinking()).isTrue();
        assertThat(mode.executeStage().deepThinking()).isFalse();
        assertThat(mode.summaryStage().deepThinking()).isFalse();
    }

    @Test
    void shouldRejectPlanExecuteWhenExecuteDeepThinkingTrue() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("execute_deep_true.json", "execute_deep_true", "execute", true);
        assertThat(loadById()).doesNotContainKey("execute_deep_true");
    }

    @Test
    void shouldRejectPlanExecuteWhenExecuteDeepThinkingFalse() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("execute_deep_false.json", "execute_deep_false", "execute", false);
        assertThat(loadById()).doesNotContainKey("execute_deep_false");
    }

    @Test
    void shouldRejectPlanExecuteWhenSummaryDeepThinkingTrue() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("summary_deep_true.json", "summary_deep_true", "summary", true);
        assertThat(loadById()).doesNotContainKey("summary_deep_true");
    }

    @Test
    void shouldRejectPlanExecuteWhenSummaryDeepThinkingFalse() throws IOException {
        writePlanExecuteWithDisallowedDeepThinking("summary_deep_false.json", "summary_deep_false", "summary", false);
        assertThat(loadById()).doesNotContainKey("summary_deep_false");
    }

    @Test
    void shouldParseSupportedRuntimePromptsAndFallbackToDefaults() throws IOException {
        Files.writeString(tempDir.resolve("runtime_prompts.json"), """
                {
                  "key": "runtime_prompts",
                  "description": "runtime prompts",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "plain prompt" },
                  "runtimePrompts": {
                    "planExecute": {
                      "taskExecutionPromptTemplate": "TASK={{task_id}}|{{task_description}}"
                    },
                    "skill": {
                      "catalogHeader": "skills-header-override"
                    },
                    "toolAppendix": {
                      "toolDescriptionTitle": "tool-desc-title-override"
                    }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        AgentDefinition definition = loader.loadAll().stream()
                .filter(item -> "runtime_prompts".equals(item.id()))
                .findFirst()
                .orElseThrow();
        SkillAppend skillAppend = definition.agentMode().skillAppend();
        ToolAppend toolAppend = definition.agentMode().toolAppend();

        assertThat(skillAppend.catalogHeader()).isEqualTo("skills-header-override");
        assertThat(skillAppend.disclosureHeader()).isEqualTo(SkillAppend.DEFAULTS.disclosureHeader());
        assertThat(toolAppend.toolDescriptionTitle()).isEqualTo("tool-desc-title-override");
        assertThat(toolAppend.afterCallHintTitle()).isEqualTo(ToolAppend.DEFAULTS.afterCallHintTitle());
    }

    @Test
    void shouldRejectRemovedVerifyAndRuntimePromptFields() throws IOException {
        Files.writeString(tempDir.resolve("removed_fields.json"), """
                {
                  "key": "removed_fields",
                  "description": "removed fields",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "verify": "NONE",
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "plain prompt" },
                  "runtimePrompts": {
                    "verify": {
                      "systemPrompt": "legacy"
                    }
                  }
                }
                """);

        assertThat(loadById()).doesNotContainKey("removed_fields");
    }

    @Test
    void shouldParseBudgetV2Config() throws IOException {
        Files.writeString(tempDir.resolve("budget_v2.json"), """
                {
                  "key": "budget_v2",
                  "description": "budget v2",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "budget": {
                    "runTimeoutMs": 180000,
                    "model": {
                      "maxCalls": 18,
                      "timeoutMs": 45000,
                      "retryCount": 2
                    },
                    "tool": {
                      "maxCalls": 36,
                      "timeoutMs": 90000,
                      "retryCount": 3
                    }
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "budget test" }
                }
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
        Files.writeString(tempDir.resolve("budget_legacy.json"), """
                {
                  "key": "budget_legacy",
                  "description": "budget legacy",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "budget": {
                    "maxModelCalls": 8,
                    "maxToolCalls": 16,
                    "timeoutMs": 120000,
                    "retryCount": 1
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "budget legacy test" }
                }
                """);

        assertThat(loadById()).doesNotContainKey("budget_legacy");
    }

    @Test
    void shouldLoadBothPlanExecuteVariantsWhenJsonFileAndUniqueKeys() throws IOException {
        Files.writeString(tempDir.resolve("demoModePlanExecute.json"), """
                {
                  "key": "demoModePlanExecute",
                  "description": "main demo",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan" },
                    "execute": { "systemPrompt": "execute" },
                    "summary": { "systemPrompt": "summary" }
                  }
                }
                """);
        Files.writeString(tempDir.resolve("demoModePlanExecuteDeepThinking.json"), """
                {
                  "key": "demoModePlanExecuteDeepThinking",
                  "description": "deep demo",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan", "deepThinking": true },
                    "execute": { "systemPrompt": "execute" },
                    "summary": { "systemPrompt": "summary" }
                  }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = newLoader(properties);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));
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

    private AgentDefinitionLoader newLoader(AgentCatalogProperties properties) {
        return new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
    }

    private void writePlanExecuteWithDisallowedDeepThinking(
            String fileName,
            String key,
            String stage,
            boolean deepThinking
    ) throws IOException {
        String deepThinkingValue = deepThinking ? "true" : "false";
        Files.writeString(tempDir.resolve(fileName), """
                {
                  "key": "%s",
                  "description": "invalid deepThinking stage config",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "mode": "PLAN_EXECUTE",
                  "planExecute": {
                    "plan": { "systemPrompt": "plan", "deepThinking": true },
                    "execute": { "systemPrompt": "execute"%s },
                    "summary": { "systemPrompt": "summary"%s }
                  }
                }
                """.formatted(
                key,
                "execute".equals(stage) ? ", \"deepThinking\": " + deepThinkingValue : "",
                "summary".equals(stage) ? ", \"deepThinking\": " + deepThinkingValue : ""
        ));
    }
}
