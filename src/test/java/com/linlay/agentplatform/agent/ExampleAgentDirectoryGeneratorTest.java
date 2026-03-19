package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleAgentDirectoryGeneratorTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateDirectoryizedAgentsWithScaffoldAndMovedPrompts() throws Exception {
        Path sourceDir = tempDir.resolve("example-agents");
        Path targetDir = tempDir.resolve("generated-agents");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("demo_plain.yml"), """
                key: demo_plain
                name: Demo Plain
                role: Demo Plain Role
                description: plain description
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: plain original prompt
                """);
        Files.writeString(sourceDir.resolve("demo_plan.yml"), """
                key: demo_plan
                name: Demo Plan
                role: Demo Plan Role
                description: plan description
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: PLAN_EXECUTE
                planExecute:
                  plan:
                    systemPrompt: plan original prompt
                  execute:
                    systemPrompt: execute original prompt
                """);

        new ExampleAgentDirectoryGenerator().generateAll(sourceDir, targetDir, LocalDate.parse("2026-03-19"));

        assertThat(Files.isDirectory(targetDir.resolve("demo_plain"))).isTrue();
        assertThat(Files.isDirectory(targetDir.resolve("demo_plan"))).isTrue();

        JsonNode plainRoot = yamlMapper.readTree(Files.readString(targetDir.resolve("demo_plain").resolve("agent.yml")));
        assertThat(plainRoot.path("modelConfig").path("modelKey").asText()).isEqualTo("bailian-qwen3-max");
        assertThat(plainRoot.path("plain").path("systemPrompt").asText())
                .isEqualTo("请遵守 SOUL.md、AGENTS.md 与 AGENTS.plain.md 中的全部指令完成任务。");
        assertThat(Files.readString(targetDir.resolve("demo_plain").resolve("AGENTS.plain.md")))
                .isEqualTo("plain original prompt");

        JsonNode planRoot = yamlMapper.readTree(Files.readString(targetDir.resolve("demo_plan").resolve("agent.yml")));
        assertThat(planRoot.path("planExecute").path("plan").path("systemPrompt").asText())
                .isEqualTo("请遵守 SOUL.md、AGENTS.md 与 AGENTS.plan.md 中的全部指令完成任务。");
        assertThat(planRoot.path("planExecute").path("execute").path("systemPrompt").asText())
                .isEqualTo("请遵守 SOUL.md、AGENTS.md 与 AGENTS.execute.md 中的全部指令完成任务。");
        assertThat(planRoot.path("planExecute").path("summary").path("systemPrompt").asText())
                .isEqualTo("请遵守 SOUL.md、AGENTS.md 与 AGENTS.summary.md 中的全部指令完成任务。");
        assertThat(Files.readString(targetDir.resolve("demo_plan").resolve("AGENTS.plan.md")))
                .isEqualTo("plan original prompt");
        assertThat(Files.readString(targetDir.resolve("demo_plan").resolve("AGENTS.execute.md")))
                .isEqualTo("execute original prompt");
        assertThat(Files.readString(targetDir.resolve("demo_plan").resolve("AGENTS.summary.md")))
                .isEqualTo("execute original prompt");

        assertThat(targetDir.resolve("demo_plain").resolve("memory").resolve("memory.md")).isRegularFile();
        assertThat(targetDir.resolve("demo_plain").resolve("memory").resolve("2026-03").resolve("2026-03-19.md")).isRegularFile();
        assertThat(targetDir.resolve("demo_plain").resolve("experiences").resolve("web-scraping").resolve("site-a-login.md")).isRegularFile();
        assertThat(targetDir.resolve("demo_plain").resolve("skills").resolve("custom_skill").resolve("scripts")).isDirectory();
        assertThat(Files.readString(targetDir.resolve("demo_plain").resolve("skills").resolve("custom_skill").resolve("SKILL.md")))
                .contains("scaffold: true");
        assertThat(Files.readString(targetDir.resolve("demo_plain").resolve("tools").resolve("custom_tool.yml")))
                .contains("scaffold: true");
    }

    @Test
    void shouldFailFastWhenTargetAgentDirectoryAlreadyExists() throws Exception {
        Path sourceDir = tempDir.resolve("example-agents");
        Path targetDir = tempDir.resolve("generated-agents");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("demo_plain.yml"), """
                key: demo_plain
                name: Demo Plain
                role: Demo Plain Role
                description: plain description
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: plain original prompt
                """);
        Files.createDirectories(targetDir.resolve("demo_plain"));

        assertThatThrownBy(() -> new ExampleAgentDirectoryGenerator().generateAll(
                sourceDir,
                targetDir,
                LocalDate.parse("2026-03-19")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Target agent directory already exists");
    }
}
