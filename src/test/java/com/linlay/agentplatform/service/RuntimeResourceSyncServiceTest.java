package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;

class RuntimeResourceSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldOverwriteBuiltInResourcesAndKeepExtraFiles() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Path toolsDir = tempDir.resolve("tools");
        Path skillsDir = tempDir.resolve("skills");
        Path schedulesDir = tempDir.resolve("schedules");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(skillsDir);
        Files.createDirectories(schedulesDir);

        Path modePlainAgent = agentsDir.resolve("demoModePlain.json");
        Path weatherTool = toolsDir.resolve("datetime.yml");
        Path skillScriptTool = toolsDir.resolve("_skill_run_script_.yml");
        Path extraAgent = agentsDir.resolve("custom_agent.json");
        Path extraTool = toolsDir.resolve("custom.yml");
        Path mathBasicSkillFile = skillsDir.resolve("math_basic").resolve("SKILL.md");
        Path extraSkillFile = skillsDir.resolve("custom_skill").resolve("SKILL.md");
        Path builtInSchedule = schedulesDir.resolve("demo_daily_summary.yml");
        Path extraSchedule = schedulesDir.resolve("custom_schedule.yml");

        Files.writeString(modePlainAgent, "old-agent-content");
        Files.writeString(weatherTool, "old-tool-content");
        Files.writeString(extraAgent, "custom agent content");
        Files.writeString(extraTool, "custom tool content");
        Files.createDirectories(mathBasicSkillFile.getParent());
        Files.createDirectories(extraSkillFile.getParent());
        Files.writeString(mathBasicSkillFile, "old-skill-content");
        Files.writeString(extraSkillFile, "custom skill content");
        Files.writeString(builtInSchedule, "old-schedule-content");
        Files.writeString(extraSchedule, "custom schedule content");

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                skillsDir,
                schedulesDir
        );
        service.syncRuntimeDirectories();
        service.syncRuntimeDirectories();

        String syncedTool = Files.readString(weatherTool);
        String syncedSkillScriptTool = Files.readString(skillScriptTool);
        String syncedSkill = Files.readString(mathBasicSkillFile);
        String syncedSchedule = Files.readString(builtInSchedule);

        assertThat(syncedTool).contains("\"name\": \"datetime\"");
        assertThat(syncedSkillScriptTool).contains("\"name\": \"_skill_run_script_\"");
        assertThat(syncedSkill).contains("name: \"math_basic\"");
        assertThat(syncedSchedule).contains("name: Demo Daily Summary")
                .contains("description: 每天早上 9 点触发 demoModePlain 输出三条摘要建议")
                .contains("cron: \"0 0 9 * * *\"");
        assertThat(skillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(schedulesDir.resolve("demo_daily_summary.yml")).exists();
        assertThat(schedulesDir.resolve("demo_viewport_weather_minutely.yml")).exists();
        assertThat(Files.readString(schedulesDir.resolve("demo_viewport_weather_minutely.yml")))
                .contains("agentKey: demoViewport")
                .contains("description: 每分钟触发一次 demoViewport，随机选择一个城市查询天气并尽量输出 viewport")
                .contains("cron: \"0 * * * * *\"");
        assertThat(toolsDir.resolve("launch_fireworks.yml")).doesNotExist();
        assertThat(toolsDir.resolve("show_modal.yml")).doesNotExist();
        assertThat(toolsDir.resolve("switch_theme.yml")).doesNotExist();
        assertThat(Files.readString(modePlainAgent)).isEqualTo("old-agent-content");
        assertThat(agentsDir.resolve("demoAction.json")).doesNotExist();
        assertThat(Files.readString(extraAgent)).isEqualTo("custom agent content");
        assertThat(Files.readString(extraTool)).isEqualTo("custom tool content");
        assertThat(Files.readString(extraSkillFile)).isEqualTo("custom skill content");
        assertThat(Files.readString(extraSchedule)).isEqualTo("custom schedule content");
    }

    @Test
    void shouldSyncToConfiguredDirectoriesInsteadOfUserDirRoot() throws Exception {
        Path configuredToolsDir = tempDir.resolve("configured").resolve("tools");
        Path configuredSkillsDir = tempDir.resolve("configured").resolve("skills");
        Path configuredSchedulesDir = tempDir.resolve("configured").resolve("schedules");
        Path legacyUserDir = tempDir.resolve("legacy-user-dir");

        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setExternalDir(configuredToolsDir.toString());
        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir(configuredSkillsDir.toString());
        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(configuredSchedulesDir.toString());

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", legacyUserDir.toString());
            RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                    toolProperties,
                    skillProperties,
                    scheduleProperties
            );
            service.syncRuntimeDirectories();
        } finally {
            if (originalUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", originalUserDir);
            }
        }

        assertThat(configuredToolsDir.resolve("datetime.yml")).exists();
        assertThat(configuredSkillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(configuredSchedulesDir.resolve("demo_daily_summary.yml")).exists();
        assertThat(configuredSchedulesDir.resolve("demo_viewport_weather_minutely.yml")).exists();
        assertThat(legacyUserDir.resolve("tools")).doesNotExist();
        assertThat(legacyUserDir.resolve("skills")).doesNotExist();
        assertThat(legacyUserDir.resolve("schedules")).doesNotExist();
    }

    @Test
    void shouldKeepLegacyBashAliasWhenCanonicalToolExists() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Path legacyBash = toolsDir.resolve("bash.yml");
        Files.writeString(legacyBash, """
                type: function
                name: _bash_
                description: legacy alias
                """);

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                tempDir.resolve("skills")
        );
        service.syncRuntimeDirectories();

        assertThat(toolsDir.resolve("_bash_.yml")).exists();
        assertThat(legacyBash).exists();
    }

    @Test
    void shouldKeepLegacySkillScriptAliasWhenCanonicalToolExists() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Path legacySkillScript = toolsDir.resolve("skill_script_run.yml");
        Files.writeString(legacySkillScript, """
                type: function
                name: skill_script_run
                description: legacy alias
                """);

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                tempDir.resolve("skills")
        );
        service.syncRuntimeDirectories();

        Path canonicalSkillScript = toolsDir.resolve("_skill_run_script_.yml");
        assertThat(canonicalSkillScript).exists();
        assertThat(Files.readString(canonicalSkillScript)).contains("\"name\": \"_skill_run_script_\"");
        assertThat(legacySkillScript).exists();
    }
}
