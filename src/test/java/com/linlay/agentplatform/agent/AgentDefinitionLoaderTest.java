package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.mode.ReactMode;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.sandbox.MountAccessMode;
import com.linlay.agentplatform.agent.runtime.sandbox.SandboxLevel;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.model.ModelDefinition;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.testsupport.TestCatalogFixtures;
import com.linlay.agentplatform.testsupport.TestModelRegistryServices;
import com.linlay.agentplatform.util.YamlCatalogSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDefinitionLoaderTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

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
        assertThat(mode.planStage().primaryPrompt()).isEqualTo("先规划");
        assertThat(mode.planStage().deepThinking()).isFalse();
        assertThat(mode.executeStage().primaryPrompt()).isEqualTo("再执行");
        assertThat(mode.summaryStage().primaryPrompt()).isEqualTo("最后总结");
    }

    @Test
    void shouldLoadDeclaredControls() throws IOException {
        writeYaml("controls_agent.yml", """
                key: controls_agent
                name: Controls Agent
                role: Controls Agent
                description: controls agent
                modelConfig:
                  modelKey: bailian-qwen3-max
                controls:
                  - key: template_id
                    type: select
                    label: 模板
                    defaultValue: TPL01
                    options:
                      - value: TPL01
                        label: 模板一
                        type: img
                      - value: TPL02
                        label: 模板二
                  - key: max_words
                    type: number
                    label: 字数
                    defaultValue: 300
                  - key: enable_thinking
                    type: switch
                    label: 深度思考
                    defaultValue: true
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        AgentDefinition definition = loadById().get("controls_agent");

        assertThat(definition).isNotNull();
        assertThat(definition.controls()).hasSize(3);
        assertThat(definition.controls().get(0).type()).isEqualTo("select");
        assertThat(definition.controls().get(0).options()).hasSize(2);
        assertThat(definition.controls().get(1).defaultValue()).isEqualTo(300);
        assertThat(definition.controls().get(2).type()).isEqualTo("switch");
        assertThat(definition.controls().get(2).defaultValue()).isEqualTo(true);
    }

    @Test
    void shouldLoadContextConfigTags() throws IOException {
        writeYaml("context_agent.yml", """
                key: context_agent
                name: Context Agent
                role: Context Agent
                description: context agent
                modelConfig:
                  modelKey: bailian-qwen3-max
                contextConfig:
                  tags:
                    - system
                    - sandbox
                    - owner
                    - all-agents
                    - unsupported_tag
                    - context
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        AgentDefinition definition = loadById().get("context_agent");

        assertThat(definition).isNotNull();
        assertThat(definition.contextTags()).containsExactly(
                RuntimeContextTags.SYSTEM,
                RuntimeContextTags.SANDBOX,
                RuntimeContextTags.OWNER,
                RuntimeContextTags.ALL_AGENTS,
                RuntimeContextTags.CONTEXT
        );
    }

    @Test
    void shouldIgnoreLegacyContextTags() throws IOException {
        writeYaml("legacy_context_agent.yml", """
                key: legacy_context_agent
                name: Legacy Context Agent
                role: Legacy Context Agent
                description: legacy context agent
                modelConfig:
                  modelKey: bailian-qwen3-max
                contextConfig:
                  tags:
                    - system_environment
                    - workspace_context
                    - session_context
                    - owner_profile
                    - auth_identity
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        AgentDefinition definition = loadById().get("legacy_context_agent");

        assertThat(definition).isNotNull();
        assertThat(definition.contextTags()).isEmpty();
    }

    @Test
    void shouldRejectInvalidControls() throws IOException {
        writeYaml("invalid_controls.yml", """
                key: invalid_controls
                name: Invalid Controls
                role: Invalid Controls
                description: invalid controls
                modelConfig:
                  modelKey: bailian-qwen3-max
                controls:
                  - key: template_id
                    type: select
                    label: 模板
                    options:
                      - label: 缺少 value
                  - key: free_text
                    type: text
                    label: 文本
                  - key: invalid_switch
                    type: switch
                    label: 开关
                    defaultValue: yes
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        assertThat(loadById()).doesNotContainKey("invalid_controls");
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
                    - _sandbox_bash_
                sandboxConfig:
                  environmentId: shell
                mode: ONESHOT
                plain:
                  systemPrompt: use sandbox
                """);

        AgentDefinition definition = loadById().get("sandboxed");

        assertThat(definition).isNotNull();
        assertThat(definition.tools()).contains("_sandbox_bash_");
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
                    - _sandbox_bash_
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
                com.linlay.agentplatform.agent.runtime.sandbox.SandboxLevel.AGENT
        );
    }

    @Test
    void shouldLoadSandboxConfigExtraMounts() throws IOException {
        writeYaml("sandboxed_extra_mounts.yml", """
                key: sandboxed_extra_mounts
                name: Sandboxed Extra Mounts
                role: Sandboxed Extra Mounts
                description: sandboxed extra mounts
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _sandbox_bash_
                sandboxConfig:
                  environmentId: shell
                  level: agent
                  extraMounts:
                    - platform: models
                      mode: ro
                    - platform: skills-market
                      mode: ro
                    - source: /tmp/datasets
                      destination: /datasets
                      mode: rw
                    - destination: /skills
                      mode: rw
                    - source: /tmp/ignored
                    - platform: tools
                      mode: invalid
                mode: ONESHOT
                plain:
                  systemPrompt: use sandbox
                """);

        AgentDefinition definition = loadById().get("sandboxed_extra_mounts");

        assertThat(definition).isNotNull();
        assertThat(definition.sandboxConfig().extraMounts()).containsExactly(
                new AgentDefinition.ExtraMount("models", null, null, MountAccessMode.RO),
                new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO),
                new AgentDefinition.ExtraMount(null, "/tmp/datasets", "/datasets", MountAccessMode.RW),
                new AgentDefinition.ExtraMount(null, null, "/skills", MountAccessMode.RW)
        );
    }

    @Test
    void shouldLoadActualDemoContainerHubValidatorDefinition() throws IOException {
        copyExampleAgentDirectory("demoContainerHubValidator");

        AgentDefinition definition = loadById().get("demoContainerHubValidator");

        assertThat(definition).isNotNull();
        assertThat(definition.name()).isEqualTo("Sandbox Validator");
        assertThat(definition.tools()).containsExactly("_sandbox_bash_");
        assertThat(definition.skills()).isEmpty();
        assertThat(definition.sandboxConfig()).isNotNull();
        assertThat(definition.sandboxConfig().environmentId()).isNull();
        assertThat(definition.sandboxConfig().level()).isNull();
        assertThat(definition.sandboxConfig().extraMounts()).isEmpty();
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.REACT);

        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.maxSteps()).isEqualTo(60);
        assertThat(mode.stage().primaryPrompt()).contains("使用 container hub 验证沙箱");
    }

    @Test
    void shouldLoadExampleDailyOfficeAssistantDefinition() throws IOException {
        copyExampleAgentDirectory("dailyOfficeAssistant");

        AgentDefinition definition = loadById().get("dailyOfficeAssistant");

        assertThat(definition).isNotNull();
        assertThat(definition.name()).isEqualTo("Daily Office Assistant");
        assertThat(definition.tools()).containsExactly("_sandbox_bash_");
        assertThat(definition.skills()).containsExactly("docx", "pptx");
        assertThat(definition.sandboxConfig()).isNotNull();
        assertThat(definition.sandboxConfig().environmentId()).isNull();
        assertThat(definition.sandboxConfig().level()).isNull();
        assertThat(definition.sandboxConfig().extraMounts()).isEmpty();
        assertThat(definition.mode()).isEqualTo(AgentRuntimeMode.REACT);

        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.maxSteps()).isEqualTo(60);
        assertThat(mode.stage().primaryPrompt()).contains("协助办公文档处理");
    }

    @Test
    void shouldLoadDirectoryAgentWithGlobalAndStageMarkdown() throws IOException {
        Path agentDir = tempDir.resolve("dir_oneshot");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_oneshot
                name: Dir Oneshot
                role: Dir Role
                description: dir oneshot
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                """);
        Files.writeString(agentDir.resolve("SOUL.md"), "soul prompt");
        Files.writeString(agentDir.resolve("AGENTS.md"), "shared prompt");

        AgentDefinition definition = loadById().get("dir_oneshot");

        assertThat(definition).isNotNull();
        assertThat(definition.soulContent()).isEqualTo("soul prompt");
        assertThat(definition.agentsContent()).isEqualTo("shared prompt");
        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().instructionsPrompt()).isEqualTo("shared prompt");
        assertThat(mode.stage().primaryPrompt()).isEqualTo("shared prompt");
    }

    @Test
    void shouldPreferTopLevelPromptFileForDirectoryOneshotAgent() throws IOException {
        Path agentDir = tempDir.resolve("dir_top_level_prompt");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_top_level_prompt
                name: Dir Top Level Prompt
                role: Dir Top Level Prompt
                description: dir top level prompt
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                promptFile: CUSTOM.md
                plain:
                  promptFile: LEGACY.md
                """);
        Files.writeString(agentDir.resolve("AGENTS.md"), "shared prompt");
        Files.writeString(agentDir.resolve("CUSTOM.md"), "custom prompt");
        Files.writeString(agentDir.resolve("LEGACY.md"), "legacy prompt");

        AgentDefinition definition = loadById().get("dir_top_level_prompt");

        assertThat(definition).isNotNull();
        assertThat(definition.agentsContent()).isEqualTo("shared prompt");
        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().instructionsPrompt()).isEqualTo("custom prompt");
        assertThat(mode.stage().primaryPrompt()).isEqualTo("custom prompt");
    }

    @Test
    void shouldConcatenateTopLevelPromptFileArrayForDirectoryReactAgent() throws IOException {
        Path agentDir = tempDir.resolve("dir_react_prompt_array");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_react_prompt_array
                name: Dir React Prompt Array
                role: Dir React Prompt Array
                description: dir react prompt array
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: REACT
                promptFile:
                  - BASE.md
                  - EMPTY.md
                  - EXTRA.md
                react:
                  maxSteps: 9
                """);
        Files.writeString(agentDir.resolve("BASE.md"), "base prompt");
        Files.writeString(agentDir.resolve("EMPTY.md"), "   \n");
        Files.writeString(agentDir.resolve("EXTRA.md"), "extra prompt");

        AgentDefinition definition = loadById().get("dir_react_prompt_array");

        assertThat(definition).isNotNull();
        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.maxSteps()).isEqualTo(9);
        assertThat(mode.stage().instructionsPrompt()).isEqualTo("base prompt\n\nextra prompt");
        assertThat(mode.stage().primaryPrompt()).isEqualTo("base prompt\n\nextra prompt");
    }

    @Test
    void shouldLoadPlanExecuteStageMarkdownFromDirectoryAgent() throws IOException {
        Path agentDir = tempDir.resolve("dir_plan");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_plan
                name: Dir Plan
                role: Dir Plan
                description: dir plan
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    promptFile: AGENTS.plan.md
                  execute:
                    promptFile: AGENTS.execute.md
                  summary:
                    promptFile: AGENTS.summary.md
                """);
        Files.writeString(agentDir.resolve("SOUL.md"), "soul prompt");
        Files.writeString(agentDir.resolve("AGENTS.plan.md"), "plan prompt file");
        Files.writeString(agentDir.resolve("AGENTS.execute.md"), "execute prompt file");
        Files.writeString(agentDir.resolve("AGENTS.summary.md"), "summary prompt file");

        AgentDefinition definition = loadById().get("dir_plan");

        assertThat(definition).isNotNull();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().instructionsPrompt()).isEqualTo("plan prompt file");
        assertThat(mode.executeStage().instructionsPrompt()).isEqualTo("execute prompt file");
        assertThat(mode.summaryStage().instructionsPrompt()).isEqualTo("summary prompt file");
    }

    @Test
    void shouldConcatenatePlanExecutePromptArraysAndFallbackToAgentsMarkdown() throws IOException {
        Path agentDir = tempDir.resolve("dir_plan_prompt_array");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_plan_prompt_array
                name: Dir Plan Prompt Array
                role: Dir Plan Prompt Array
                description: dir plan prompt array
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    promptFile:
                      - PLAN.base.md
                      - PLAN.extra.md
                  execute: {}
                  summary:
                    promptFile:
                      - SUMMARY.base.md
                      - SUMMARY.extra.md
                """);
        Files.writeString(agentDir.resolve("AGENTS.md"), "shared prompt");
        Files.writeString(agentDir.resolve("PLAN.base.md"), "plan base");
        Files.writeString(agentDir.resolve("PLAN.extra.md"), "plan extra");
        Files.writeString(agentDir.resolve("SUMMARY.base.md"), "summary base");
        Files.writeString(agentDir.resolve("SUMMARY.extra.md"), "summary extra");

        AgentDefinition definition = loadById().get("dir_plan_prompt_array");

        assertThat(definition).isNotNull();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().instructionsPrompt()).isEqualTo("plan base\n\nplan extra");
        assertThat(mode.executeStage().instructionsPrompt()).isEqualTo("shared prompt");
        assertThat(mode.summaryStage().instructionsPrompt()).isEqualTo("summary base\n\nsummary extra");
    }

    @Test
    void shouldRejectHiddenPromptFileReferencedFromPromptFileArray() throws IOException {
        Path agentDir = tempDir.resolve("dir_hidden_prompt_array");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_hidden_prompt_array
                name: Dir Hidden Prompt Array
                role: Dir Hidden Prompt Array
                description: dir hidden prompt array
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                promptFile:
                  - SAFE.md
                  - .hidden.md
                """);
        Files.writeString(agentDir.resolve("SAFE.md"), "safe prompt");
        Files.writeString(agentDir.resolve(".hidden.md"), "hidden prompt");

        assertThat(loadById()).doesNotContainKey("dir_hidden_prompt_array");
    }

    @Test
    void shouldIgnoreScaffoldPerAgentSkillPlaceholderFromDirectoryAgent() throws IOException {
        Path agentDir = tempDir.resolve("dir_skill");
        Files.createDirectories(agentDir.resolve("skills").resolve("custom_skill"));
        Files.createDirectories(agentDir.resolve("skills").resolve("real_skill"));
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: dir_skill
                name: Dir Skill
                role: Dir Skill
                description: dir skill
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                """);
        Files.writeString(agentDir.resolve("AGENTS.md"), "skill prompt");
        Files.writeString(agentDir.resolve("skills").resolve("custom_skill").resolve("SKILL.md"), """
                ---
                name: "Custom Skill"
                description: "placeholder"
                scaffold: true
                ---
                """);
        Files.writeString(agentDir.resolve("skills").resolve("real_skill").resolve("SKILL.md"), """
                ---
                name: "Real Skill"
                description: "real"
                ---
                body
                """);

        AgentDefinition definition = loadById().get("dir_skill");

        assertThat(definition).isNotNull();
        assertThat(definition.perAgentSkills()).containsExactly("real_skill");
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
        assertThat(mode.planStage().primaryPrompt()).isEqualTo("先规划\n再拆解");
        assertThat(mode.executeStage().primaryPrompt()).isEqualTo("执行任务");
        assertThat(mode.summaryStage().primaryPrompt()).isEqualTo("总结结果");
    }

    @Test
    void shouldResolveStageAndTopLevelMaxTokensFromAgentConfig() throws IOException {
        writeYaml("yaml_max_tokens.yml", """
                key: yaml_max_tokens
                name: YAML Max Tokens
                role: YAML Max Tokens
                description: yaml max tokens
                modelConfig:
                  modelKey: custom-qwen
                  max_tokens: 9000
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: 先规划
                  execute:
                    systemPrompt: 执行任务
                    modelConfig:
                      modelKey: custom-qwen
                      max_tokens: 12000
                  summary:
                    systemPrompt: 总结结果
                """);

        ModelRegistryService registry = TestModelRegistryServices.registry(new ModelDefinition(
                "custom-qwen",
                "custom",
                ModelProtocol.OPENAI,
                "custom-qwen",
                false,
                true,
                null,
                null,
                16000,
                null,
                null,
                null
        ));

        PlanExecuteMode mode = (PlanExecuteMode) loadById(registry).get("yaml_max_tokens").agentMode();

        assertThat(mode.planStage().maxTokens()).isEqualTo(9000);
        assertThat(mode.executeStage().maxTokens()).isEqualTo(12000);
        assertThat(mode.summaryStage().maxTokens()).isEqualTo(9000);
    }

    @Test
    void shouldFallbackToModelRegistryMaxTokensWhenAgentDoesNotDeclareIt() throws IOException {
        writeYaml("yaml_model_fallback.yml", """
                key: yaml_model_fallback
                name: YAML Model Fallback
                role: YAML Model Fallback
                description: yaml model fallback
                modelConfig:
                  modelKey: custom-qwen
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        ModelRegistryService registry = TestModelRegistryServices.registry(new ModelDefinition(
                "custom-qwen",
                "custom",
                ModelProtocol.OPENAI,
                "custom-qwen",
                false,
                true,
                null,
                null,
                16000,
                null,
                null,
                null
        ));

        OneshotMode mode = (OneshotMode) loadById(registry).get("yaml_model_fallback").agentMode();

        assertThat(mode.stage().maxTokens()).isEqualTo(16000);
    }

    @Test
    void shouldIgnoreLegacyJsonFiles() throws IOException {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {
                  "key": "legacy",
                  "name": "legacy",
                  "role": "legacy",
                  "description": "legacy"
                }
                """);
        writeYaml("valid.yml", """
                key: valid
                name: Valid
                role: Valid
                description: valid
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: valid prompt
                """);

        assertThat(loadById()).containsKey("valid");
    }

    @Test
    void shouldSkipDirectoryAgentWhenDirectoryNameDoesNotMatchKey() throws IOException {
        Path validDir = tempDir.resolve("valid_agent");
        Files.createDirectories(validDir);
        Files.writeString(validDir.resolve("agent.yml"), """
                key: valid_agent
                name: Valid Agent
                role: Valid Agent
                description: valid
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                """);
        Files.writeString(validDir.resolve("AGENTS.md"), "valid prompt");

        Path mismatchedDir = tempDir.resolve("action.demo");
        Files.createDirectories(mismatchedDir);
        Files.writeString(mismatchedDir.resolve("agent.yml"), """
                key: demoAction
                name: Demo Action
                role: Demo Action
                description: mismatch
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                """);
        Files.writeString(mismatchedDir.resolve("AGENTS.md"), "mismatched prompt");

        Map<String, AgentDefinition> byId = loadById();

        assertThat(byId).containsOnlyKeys("valid_agent");
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
        assertThat(definition.tools()).containsExactly("_sandbox_bash_");
        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().tools()).containsExactly("_sandbox_bash_");
    }

    @Test
    void shouldInjectSandboxToolForDeclaredSkillsWithoutDuplicatingExplicitConfig() throws IOException {
        writeYaml("skills_with_explicit_sandbox_tool.yml", """
                key: skills_with_explicit_sandbox_tool
                name: Skills With Explicit Sandbox Tool
                role: Skills With Explicit Sandbox Tool
                description: explicit sandbox tool + skills
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _sandbox_bash_
                skillConfig:
                  skills:
                    - docx
                mode: REACT
                react:
                  systemPrompt: test prompt
                """);

        AgentDefinition definition = loadById().get("skills_with_explicit_sandbox_tool");

        assertThat(definition).isNotNull();
        assertThat(definition.skills()).containsExactly("docx");
        assertThat(definition.tools()).containsExactly("_sandbox_bash_");
        ReactMode mode = (ReactMode) definition.agentMode();
        assertThat(mode.stage().tools()).containsExactly("_sandbox_bash_");
    }

    @Test
    void shouldNotInjectSandboxToolForPerAgentSkillsOnly() throws IOException {
        Path agentDir = tempDir.resolve("per_agent_skills_only");
        Path skillsDir = agentDir.resolve("skills").resolve("local_only");
        Files.createDirectories(skillsDir);
        Files.writeString(agentDir.resolve("agent.yml"), """
                key: per_agent_skills_only
                name: Per Agent Skills Only
                role: Per Agent Skills Only
                description: per-agent skills only
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: local skill prompt
                """);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: "local_only"
                description: "demo"
                ---

                local only skill
                """);

        AgentDefinition definition = loadById().get("per_agent_skills_only");

        assertThat(definition).isNotNull();
        assertThat(definition.skills()).isEmpty();
        assertThat(definition.perAgentSkills()).containsExactly("local_only");
        assertThat(definition.tools()).doesNotContain("_sandbox_bash_");
        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().tools()).doesNotContain("_sandbox_bash_");
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
                    - _datetime_
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
        assertThat(mode.planStage().tools()).containsExactlyInAnyOrder("_bash_", "_datetime_", "_plan_add_tasks_");

        assertThat(mode.executeStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.executeStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.executeStage().tools()).containsExactly("_plan_update_task_");

        assertThat(mode.summaryStage().providerKey()).isEqualTo("bailian");
        assertThat(mode.summaryStage().model()).isEqualTo("qwen3-max");
        assertThat(mode.summaryStage().tools()).containsExactlyInAnyOrder("_bash_", "_datetime_");
        assertThat(definition.tools()).contains("_plan_add_tasks_", "_plan_update_task_");
    }

    @Test
    void shouldInjectSandboxToolForSkillsAcrossAllStagesAndPreserveItWhenToolConfigIsNull() throws IOException {
        writeYaml("skills_stage_injection.yml", """
                key: skills_stage_injection
                name: Skills Stage Injection
                role: Skills Stage Injection
                description: skills stage injection
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _datetime_
                skillConfig:
                  skills:
                    - docx
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

        AgentDefinition definition = loadById().get("skills_stage_injection");

        assertThat(definition).isNotNull();
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().tools()).containsExactlyInAnyOrder("_datetime_", "_plan_add_tasks_", "_sandbox_bash_");
        assertThat(mode.executeStage().tools()).containsExactlyInAnyOrder("_plan_update_task_", "_sandbox_bash_");
        assertThat(mode.summaryStage().tools()).containsExactlyInAnyOrder("_datetime_", "_sandbox_bash_");
        assertThat(definition.tools()).contains("_sandbox_bash_", "_plan_add_tasks_", "_plan_update_task_");
    }

    @Test
    void shouldNotInjectMemoryToolsWhenOnlyMemoryContextTagDeclared() throws IOException {
        writeYaml("memory_stage_injection.yml", """
                key: memory_stage_injection
                name: Memory Stage Injection
                role: Memory Stage Injection
                description: memory stage injection
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _datetime_
                contextConfig:
                  tags:
                    - memory
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

        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setEnabled(true);
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(
                new ObjectMapper(),
                properties,
                TestModelRegistryServices.standardRegistry(),
                memoryProperties
        );

        AgentDefinition definition = loader.loadAll().stream()
                .filter(candidate -> "memory_stage_injection".equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
        assertThat(mode.executeStage().tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
        assertThat(mode.summaryStage().tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
    }

    @Test
    void shouldInjectMemoryToolsWhenMemoryConfigEnabled() throws IOException {
        writeYaml("memory_config_enabled.yml", """
                key: memory_config_enabled
                name: Memory Config Enabled
                role: Memory Config Enabled
                description: memory config enabled
                modelConfig:
                  modelKey: bailian-qwen3-max
                toolConfig:
                  backends:
                    - _datetime_
                memoryConfig:
                  enabled: true
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

        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setEnabled(true);
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(
                new ObjectMapper(),
                properties,
                TestModelRegistryServices.standardRegistry(),
                memoryProperties
        );

        AgentDefinition definition = loader.loadAll().stream()
                .filter(candidate -> "memory_config_enabled".equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.tools()).contains("_memory_write_", "_memory_read_", "_memory_search_");
        PlanExecuteMode mode = (PlanExecuteMode) definition.agentMode();
        assertThat(mode.planStage().tools()).contains("_memory_write_", "_memory_read_", "_memory_search_");
        assertThat(mode.executeStage().tools()).contains("_memory_write_", "_memory_read_", "_memory_search_");
        assertThat(mode.summaryStage().tools()).contains("_memory_write_", "_memory_read_", "_memory_search_");
    }

    @Test
    void shouldSkipMemoryToolInjectionWhenMemoryFeatureDisabled() throws IOException {
        writeYaml("memory_disabled.yml", """
                key: memory_disabled
                name: Memory Disabled
                role: Memory Disabled
                description: memory disabled
                modelConfig:
                  modelKey: bailian-qwen3-max
                memoryConfig:
                  enabled: true
                mode: ONESHOT
                plain:
                  systemPrompt: test
                """);

        AgentMemoryProperties memoryProperties = new AgentMemoryProperties();
        memoryProperties.setEnabled(false);
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(
                new ObjectMapper(),
                properties,
                TestModelRegistryServices.standardRegistry(),
                memoryProperties
        );

        AgentDefinition definition = loader.loadAll().stream()
                .filter(candidate -> "memory_disabled".equals(candidate.id()))
                .findFirst()
                .orElseThrow();

        assertThat(definition.tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
        OneshotMode mode = (OneshotMode) definition.agentMode();
        assertThat(mode.stage().tools()).doesNotContain("_memory_write_", "_memory_read_", "_memory_search_");
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
                    - _datetime_
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
    void shouldParseDailyOfficeAssistantBudgetOverride() throws IOException {
        writeYaml("dailyOfficeAssistant.yml", """
                key: dailyOfficeAssistant
                name: Daily Office Assistant
                role: Office Assistant
                description: daily office assistant
                modelConfig:
                  modelKey: bailian-qwen3-max
                budget:
                  runTimeoutMs: 1800000
                  model:
                    maxCalls: 100
                  tool:
                    maxCalls: 60
                mode: REACT
                react:
                  systemPrompt: assist office tasks
                """);

        AgentDefinition definition = loadById().get("dailyOfficeAssistant");

        assertThat(definition).isNotNull();
        assertThat(definition.runSpec().budget().runTimeoutMs()).isEqualTo(1_800_000L);
        assertThat(definition.runSpec().budget().model().maxCalls()).isEqualTo(100);
        assertThat(definition.runSpec().budget().tool().maxCalls()).isEqualTo(60);
        assertThat(definition.runSpec().budget().tool().timeoutMs()).isEqualTo(120_000L);
    }

    @Test
    void shouldParseDailyOfficeProAssistantBudgetOverride() throws IOException {
        writeYaml("dailyOfficeProAssistant.yml", """
                key: dailyOfficeProAssistant
                name: Daily Office Pro Assistant
                role: Office Assistant Pro
                description: daily office pro assistant
                modelConfig:
                  modelKey: bailian-qwen3-max
                budget:
                  runTimeoutMs: 1800000
                  model:
                    maxCalls: 100
                  tool:
                    maxCalls: 60
                mode: REACT
                react:
                  systemPrompt: assist office tasks
                """);

        AgentDefinition definition = loadById().get("dailyOfficeProAssistant");

        assertThat(definition).isNotNull();
        assertThat(definition.runSpec().budget().runTimeoutMs()).isEqualTo(1_800_000L);
        assertThat(definition.runSpec().budget().model().maxCalls()).isEqualTo(100);
        assertThat(definition.runSpec().budget().tool().maxCalls()).isEqualTo(60);
        assertThat(definition.runSpec().budget().tool().timeoutMs()).isEqualTo(120_000L);
    }

    @Test
    void shouldMergeAgentBudgetWithConfiguredDefaults() throws IOException {
        writeYaml("merged_budget.yml", """
                key: merged_budget
                name: Merged Budget
                role: Merged Budget
                description: merged budget
                modelConfig:
                  modelKey: bailian-qwen3-max
                budget:
                  runTimeoutMs: 700000
                  model:
                    maxCalls: 45
                mode: REACT
                react:
                  systemPrompt: merged budget prompt
                """);

        AgentDefaultsProperties defaults = new AgentDefaultsProperties();
        defaults.getBudget().setRunTimeoutMs(500_000L);
        defaults.getBudget().getModel().setMaxCalls(40);
        defaults.getBudget().getModel().setTimeoutMs(90_000L);
        defaults.getBudget().getTool().setMaxCalls(70);
        defaults.getBudget().getTool().setTimeoutMs(480_000L);
        defaults.getReact().setMaxSteps(72);

        AgentDefinition definition = loadById(defaults).get("merged_budget");

        assertThat(definition).isNotNull();
        assertThat(definition.runSpec().budget().runTimeoutMs()).isEqualTo(700_000L);
        assertThat(definition.runSpec().budget().model().maxCalls()).isEqualTo(45);
        assertThat(definition.runSpec().budget().model().timeoutMs()).isEqualTo(90_000L);
        assertThat(definition.runSpec().budget().tool().maxCalls()).isEqualTo(70);
        assertThat(definition.runSpec().budget().tool().timeoutMs()).isEqualTo(480_000L);
        assertThat(((ReactMode) definition.agentMode()).maxSteps()).isEqualTo(72);
    }

    @Test
    void shouldUseConfiguredPlanExecuteDefaultMaxSteps() throws IOException {
        writeYaml("plan_execute_defaults.yml", """
                key: plan_execute_defaults
                name: Plan Execute Defaults
                role: Plan Execute Defaults
                description: defaults
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

        AgentDefaultsProperties defaults = new AgentDefaultsProperties();
        defaults.getPlanExecute().setMaxSteps(72);
        defaults.getPlanExecute().setMaxWorkRoundsPerTask(9);

        AgentDefinition definition = loadById(defaults).get("plan_execute_defaults");

        assertThat(definition).isNotNull();
        assertThat(((PlanExecuteMode) definition.agentMode()).maxSteps()).isEqualTo(72);
        assertThat(((PlanExecuteMode) definition.agentMode()).maxWorkRoundsPerTask()).isEqualTo(9);
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

    @Test
    void shouldIgnoreExampleAgentDirectoryAndLoadDemoAgentDirectory() throws IOException {
        Path ignoredDir = tempDir.resolve("ignored.example");
        Files.createDirectories(ignoredDir);
        Files.writeString(ignoredDir.resolve("agent.yml"), """
                key: ignored.example
                name: Ignored Example
                role: Ignored Example
                description: template
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: ignored
                """);

        Path liveDir = tempDir.resolve("live.demo");
        Files.createDirectories(liveDir);
        Files.writeString(liveDir.resolve("agent.yml"), """
                key: live.demo
                name: Live Demo
                role: Live Demo
                description: runtime demo
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: loaded
                """);

        Map<String, AgentDefinition> byId = loadById();

        assertThat(byId).doesNotContainKey("ignored.example");
        assertThat(byId).containsKey("live.demo");
    }

    private Map<String, AgentDefinition> loadById() {
        return loadById(new AgentDefaultsProperties());
    }

    private Map<String, AgentDefinition> loadById(AgentDefaultsProperties defaults) {
        return loadById(TestModelRegistryServices.standardRegistry(), defaults);
    }

    private Map<String, AgentDefinition> loadById(ModelRegistryService modelRegistryService) {
        return loadById(modelRegistryService, new AgentDefaultsProperties());
    }

    private Map<String, AgentDefinition> loadById(ModelRegistryService modelRegistryService, AgentDefaultsProperties defaults) {
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(tempDir.toString());
        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, modelRegistryService, defaults);
        return loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));
    }

    private AgentDefinitionLoader newLoader(AgentProperties properties) {
        return newLoader(properties, new AgentDefaultsProperties());
    }

    private AgentDefinitionLoader newLoader(AgentProperties properties, AgentDefaultsProperties defaults) {
        return new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry(), defaults);
    }

    private void writeYaml(String fileName, String content) throws IOException {
        String lowerName = fileName.toLowerCase();
        String targetName = lowerName.endsWith(".yaml") ? "agent.yaml" : "agent.yml";
        Path agentDir = tempDir.resolve(resolveAgentDirectoryName(fileName, content));
        Files.createDirectories(agentDir);

        if (canTransformLegacySystemPromptFixture(content)) {
            if (tryWriteTransformedDirectoryAgent(agentDir, targetName, content)) {
                return;
            }
        }
        Files.writeString(agentDir.resolve(targetName), content);
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

    private boolean canTransformLegacySystemPromptFixture(String content) {
        return !YamlCatalogSupport.validateHeader(content, List.of("key", "name", "role", "description")).isPresent();
    }

    private String resolveAgentDirectoryName(String fileName, String content) {
        try {
            Map<String, Object> root = yamlMapper.readValue(content, LinkedHashMap.class);
            Object key = root == null ? null : root.get("key");
            if (key != null && !String.valueOf(key).isBlank()) {
                return String.valueOf(key).trim();
            }
        } catch (Exception ignored) {
            // Fall back to the source filename when the fixture is intentionally invalid.
        }
        return YamlCatalogSupport.fileBaseName(Path.of(fileName));
    }

    @SuppressWarnings("unchecked")
    private boolean tryWriteTransformedDirectoryAgent(Path agentDir, String targetName, String rawContent) throws IOException {
        Map<String, Object> root;
        try {
            root = yamlMapper.readValue(rawContent, LinkedHashMap.class);
        } catch (Exception ex) {
            return false;
        }
        if (root == null || root.isEmpty() || !root.containsKey("mode")) {
            return false;
        }

        String mode = String.valueOf(root.get("mode"));
        boolean writePromptFiles = !("agent.yaml".equals(targetName) && Files.exists(agentDir.resolve("agent.yml")));
        switch (mode) {
            case "ONESHOT" -> {
                Map<String, Object> plain = (Map<String, Object>) root.get("plain");
                String prompt = removeSystemPrompt(plain);
                if (writePromptFiles && prompt != null) {
                    Files.writeString(agentDir.resolve("AGENTS.md"), prompt);
                }
                if (plain == null || plain.isEmpty()) {
                    root.remove("plain");
                }
            }
            case "REACT" -> {
                Map<String, Object> react = (Map<String, Object>) root.get("react");
                String prompt = removeSystemPrompt(react);
                if (writePromptFiles && prompt != null) {
                    Files.writeString(agentDir.resolve("AGENTS.md"), prompt);
                }
                if (react == null || react.isEmpty()) {
                    root.remove("react");
                }
            }
            case "PLAN_EXECUTE" -> {
                Map<String, Object> planExecute = (Map<String, Object>) root.get("planExecute");
                if (planExecute == null) {
                    return false;
                }
                rewritePlanStage(agentDir, planExecute, "plan", "AGENTS.plan.md", writePromptFiles);
                rewritePlanStage(agentDir, planExecute, "execute", "AGENTS.execute.md", writePromptFiles);
                rewritePlanStage(agentDir, planExecute, "summary", "AGENTS.summary.md", writePromptFiles);
            }
            default -> {
                return false;
            }
        }

        Files.writeString(agentDir.resolve(targetName), yamlMapper.writeValueAsString(root).replaceFirst("^---\\n", ""));
        return true;
    }

    @SuppressWarnings("unchecked")
    private void rewritePlanStage(
            Path agentDir,
            Map<String, Object> planExecute,
            String stageName,
            String promptFile,
            boolean writePromptFile
    ) throws IOException {
        Map<String, Object> stage = (Map<String, Object>) planExecute.get(stageName);
        if (stage == null) {
            return;
        }
        String prompt = removeSystemPrompt(stage);
        if (writePromptFile && prompt != null) {
            Files.writeString(agentDir.resolve(promptFile), prompt);
        }
        stage.put("promptFile", promptFile);
    }

    private String removeSystemPrompt(Map<String, Object> stage) {
        if (stage == null) {
            return null;
        }
        Object prompt = stage.remove("systemPrompt");
        if (prompt == null) {
            return null;
        }
        String value = String.valueOf(prompt).trim();
        return value.isEmpty() ? null : value + "\n";
    }

    private void copyExampleAgentDirectory(String key) throws IOException {
        Path sourceDir = TestCatalogFixtures.agentsDir().resolve(key);
        Path targetDir = tempDir.resolve(key);
        Files.createDirectories(targetDir);
        try (var walk = Files.walk(sourceDir)) {
            for (Path source : walk.toList()) {
                Path target = targetDir.resolve(sourceDir.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }
}
