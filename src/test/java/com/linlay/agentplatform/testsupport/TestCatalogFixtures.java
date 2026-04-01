package com.linlay.agentplatform.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestCatalogFixtures {

    private static final Path AGENTS_DIR = prepareAgentsDir();
    private static final Path MODELS_DIR = prepareModelsDir();
    private static final Path SKILLS_DIR = prepareSkillsDir();

    private TestCatalogFixtures() {
    }

    public static Path agentsDir() {
        return AGENTS_DIR;
    }

    public static Path modelsDir() {
        return MODELS_DIR;
    }

    public static Path skillsDir() {
        return SKILLS_DIR;
    }

    private static Path prepareAgentsDir() {
        try {
            Path dir = Files.createTempDirectory("agent-platform-runner-test-agents-");
            writeAgent(dir, "demoModePlain", """
                    key: demoModePlain
                    name: Jarvis
                    role: 单次直答示例
                    description: 测试用单轮回答 agent
                    icon:
                      name: bot
                      color: blue
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    mode: ONESHOT
                    plain:
                      systemPrompt: 请直接回答用户问题
                    """);
            writeAgent(dir, "demoModeReact", """
                    key: demoModeReact
                    name: Luna
                    role: REACT示例
                    description: 测试用 react agent
                    icon:
                      name: orbit
                      color: indigo
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    controls:
                      - key: answer_style
                        type: select
                        label: 输出风格
                        defaultValue: standard
                        options:
                          - value: concise
                            label: 简洁
                          - value: standard
                            label: 标准
                          - value: detailed
                            label: 详细
                      - key: step_budget
                        type: number
                        label: 推理步数
                        defaultValue: 6
                      - key: enable_voice_progress
                        type: switch
                        label: 语音播报进展
                        defaultValue: true
                    toolConfig:
                      backends:
                        - datetime
                    mode: REACT
                    react:
                      systemPrompt: 请按 react 方式逐步处理
                    """);
            writeAgent(dir, "demoModePlanExecute", """
                    key: demoModePlanExecute
                    name: 星策
                    role: 规划执行示例
                    description: 测试用 plan execute agent
                    icon:
                      name: route
                      color: green
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
                        type: boolean
                        label: 深度思考
                        defaultValue: true
                    mode: PLAN_EXECUTE
                    planExecute:
                      plan:
                        systemPrompt: 先规划
                      execute:
                        systemPrompt: 再执行
                      summary:
                        systemPrompt: 最后总结
                    """);
            writeAgent(dir, "demoViewport", """
                    key: demoViewport
                    name: 极光
                    role: 视图渲染示例
                    description: 测试用 viewport agent
                    icon:
                      name: layout
                      color: cyan
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    toolConfig:
                      backends:
                        - datetime
                    mode: REACT
                    react:
                      systemPrompt: 需要时触发视图工具
                    """);
            writeAgent(dir, "demoAction", """
                    key: demoAction
                    name: 小焰
                    role: UI动作示例
                    description: 测试用 action agent
                    icon:
                      name: bolt
                      color: amber
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    toolConfig:
                      actions:
                        - switch_theme
                    mode: ONESHOT
                    plain:
                      systemPrompt: 需要时触发 action 工具
                    """);
            writeAgent(dir, "demoScheduleManager", """
                    key: demoScheduleManager
                    name: 排程助手
                    role: 调度示例
                    description: 测试用 schedule manager
                    icon:
                      name: calendar
                      color: teal
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    mode: PLAN_EXECUTE
                    planExecute:
                      plan:
                        systemPrompt: 先规划排程
                      execute:
                        systemPrompt: 再执行排程
                      summary:
                        systemPrompt: 最后总结排程
                    """);
            writeAgent(dir, "demoContainerHubValidator", """
                    key: demoContainerHubValidator
                    name: Sandbox Validator
                    role: Container Hub 验证
                    description: 测试用 container hub validator
                    icon:
                      name: shield
                      color: slate
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    toolConfig:
                      backends:
                        - _sandbox_bash_
                    mode: REACT
                    react:
                      systemPrompt: 使用 container hub 验证沙箱
                    """);
            writeAgent(dir, "demoDatabase", """
                    key: demoDatabase
                    name: 数枢
                    role: 数据库助手示例
                    description: 测试用 database agent
                    icon:
                      name: database
                      color: emerald
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    mode: REACT
                    react:
                      systemPrompt: 协助数据库查询
                    """);
            writeAgent(dir, "demoMail", """
                    key: demoMail
                    name: Mail Agent
                    role: 邮件示例
                    description: 测试用 mail agent
                    icon:
                      name: mail
                      color: rose
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    mode: REACT
                    react:
                      systemPrompt: 协助邮件场景
                    """);
            writeAgent(dir, "demoImageGenerator", """
                    key: demoImageGenerator
                    name: Imagine
                    role: 图片生成示例
                    description: 测试用 image generator
                    icon:
                      name: image
                      color: fuchsia
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    mode: ONESHOT
                    plain:
                      systemPrompt: 协助图片生成场景
                    """);
            writeAgent(dir, "dailyOfficeAssistant", """
                    key: dailyOfficeAssistant
                    name: Daily Office Assistant
                    role: 办公文档助手
                    description: 测试用 office assistant
                    icon:
                      name: briefcase
                      color: sky
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    skillConfig:
                      skills:
                        - docx
                        - pptx
                    mode: REACT
                    react:
                      systemPrompt: 协助办公文档处理
                    """);
            writeAgent(dir, "demoConfirmDialog", """
                    key: demoConfirmDialog
                    name: 灵犀
                    role: 确认对话示例
                    description: 测试用确认对话 agent
                    icon:
                      name: message-circle
                      color: violet
                    modelConfig:
                      modelKey: bailian-qwen3-max
                    toolConfig:
                      frontends:
                        - confirm_dialog
                    mode: REACT
                    react:
                      systemPrompt: 需要时发起确认对话
                    """);
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Path prepareModelsDir() {
        try {
            Path dir = Files.createTempDirectory("agent-platform-runner-test-models-");
            Files.writeString(dir.resolve("bailian-qwen3-max.yml"), """
                    key: bailian-qwen3-max
                    provider: bailian
                    protocol: OPENAI
                    modelId: qwen3-max
                    isReasoner: true
                    isFunction: true
                    pricing:
                      promptPointsPer1k: 10
                      completionPointsPer1k: 30
                      perCallPoints: 1
                      priceRatio: 1.0
                    """);
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Path prepareSkillsDir() {
        try {
            Path dir = Files.createTempDirectory("agent-platform-runner-test-skills-");
            writeSkill(dir, "docx", "DOCX Skill", "read and write docx");
            writeSkill(dir, "pptx", "PPTX Skill", "read and write pptx");
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void writeAgent(Path root, String key, String content) throws IOException {
        Path agentDir = root.resolve(key);
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), content);
    }

    private static void writeSkill(Path root, String key, String name, String description) throws IOException {
        Path skillDir = root.resolve(key);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "%s"
                description: "%s"
                ---
                # %s
                test fixture
                """.formatted(name, description, name));
    }
}
