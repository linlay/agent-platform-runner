package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
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
        Path viewportsDir = tempDir.resolve("viewports");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(skillsDir);
        Files.createDirectories(schedulesDir);
        Files.createDirectories(viewportsDir);

        Path modePlainAgent = agentsDir.resolve("demoModePlain.yml");
        Path weatherTool = toolsDir.resolve("datetime.yml");
        Path extraAgent = agentsDir.resolve("custom_agent.yml");
        Path extraTool = toolsDir.resolve("custom.yml");
        Path builtInViewport = viewportsDir.resolve("weather_card.html");
        Path extraViewport = viewportsDir.resolve("custom_view.html");
        Path containerHubSkillFile = skillsDir.resolve("container_hub_validation").resolve("SKILL.md");
        Path extraSkillFile = skillsDir.resolve("custom_skill").resolve("SKILL.md");
        Path builtInSchedule = schedulesDir.resolve("builtin_placeholder_daily.yml");
        Path extraSchedule = schedulesDir.resolve("custom_schedule.yml");
        Files.writeString(modePlainAgent, "old-agent-content");
        Files.writeString(weatherTool, "old-tool-content");
        Files.writeString(extraAgent, "custom agent content");
        Files.writeString(extraTool, "custom tool content");
        Files.writeString(builtInViewport, "old-viewport-content");
        Files.writeString(extraViewport, "custom viewport content");
        Files.createDirectories(containerHubSkillFile.getParent());
        Files.createDirectories(extraSkillFile.getParent());
        Files.writeString(containerHubSkillFile, "old-skill-content");
        Files.writeString(extraSkillFile, "custom skill content");
        Files.writeString(builtInSchedule, "old-schedule-content");
        Files.writeString(extraSchedule, "custom schedule content");

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                skillsDir,
                schedulesDir,
                viewportsDir
        );
        service.syncRuntimeDirectories();
        service.syncRuntimeDirectories();

        String syncedTool = Files.readString(weatherTool);
        String syncedViewport = Files.readString(builtInViewport);
        String syncedSkill = Files.readString(containerHubSkillFile);
        String syncedSchedule = Files.readString(builtInSchedule);

        assertThat(syncedTool).contains("name: datetime");
        assertThat(syncedViewport).contains("<div>sunny</div>");
        assertThat(syncedSkill).contains("name: \"container_hub_validation\"");
        assertThat(syncedSchedule).contains("name: 内置占位计划任务")
                .contains("description: 预留内置计划任务同步链路的占位任务，默认禁用")
                .contains("cron: \"0 0 0 * * *\"")
                .contains("environment:")
                .contains("query:");
        assertThat(skillsDir.resolve("container_hub_validation").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("docx").resolve("SKILL.md")).doesNotExist();
        assertThat(skillsDir.resolve("pptx").resolve("SKILL.md")).doesNotExist();
        assertThat(skillsDir.resolve("screenshot").resolve("SKILL.md")).doesNotExist();
        assertThat(skillsDir.resolve("slack-gif-creator").resolve("SKILL.md")).doesNotExist();
        assertThat(schedulesDir.resolve("builtin_placeholder_daily.yml")).exists();
        assertThat(toolsDir.resolve("_plan_add_tasks_.yml")).exists();
        assertThat(toolsDir.resolve("_plan_update_task_.yml")).exists();
        assertThat(toolsDir.resolve("launch_fireworks.yml")).exists();
        assertThat(toolsDir.resolve("show_modal.yml")).exists();
        assertThat(toolsDir.resolve("switch_theme.yml")).exists();
        assertThat(viewportsDir.resolve("weather_card.html")).exists();
        assertThat(viewportsDir.resolve("flight_form.qlc")).exists();
        assertThat(Files.readString(modePlainAgent)).isEqualTo("old-agent-content");
        assertThat(agentsDir.resolve("demoAction.yml")).doesNotExist();
        assertThat(Files.readString(extraAgent)).isEqualTo("custom agent content");
        assertThat(Files.readString(extraTool)).isEqualTo("custom tool content");
        assertThat(Files.readString(extraViewport)).isEqualTo("custom viewport content");
        assertThat(Files.readString(extraSkillFile)).isEqualTo("custom skill content");
        assertThat(Files.readString(extraSchedule)).isEqualTo("custom schedule content");
    }

    @Test
    void shouldSyncToConfiguredDirectoriesInsteadOfUserDirRoot() throws Exception {
        Path configuredToolsDir = tempDir.resolve("configured").resolve("tools");
        Path configuredSkillsDir = tempDir.resolve("configured").resolve("skills");
        Path configuredSchedulesDir = tempDir.resolve("configured").resolve("schedules");
        Path configuredViewportsDir = tempDir.resolve("configured").resolve("viewports");
        Path legacyUserDir = tempDir.resolve("legacy-user-dir");

        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setExternalDir(configuredToolsDir.toString());
        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir(configuredSkillsDir.toString());
        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(configuredSchedulesDir.toString());
        ViewportProperties viewportProperties = new ViewportProperties();
        viewportProperties.setExternalDir(configuredViewportsDir.toString());

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", legacyUserDir.toString());
            RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                    toolProperties,
                    skillProperties,
                    scheduleProperties,
                    viewportProperties
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
        assertThat(configuredSkillsDir.resolve("container_hub_validation").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("docx").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("pptx").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("screenshot").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("slack-gif-creator").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSchedulesDir.resolve("builtin_placeholder_daily.yml")).exists();
        assertThat(configuredViewportsDir.resolve("weather_card.html")).exists();
        assertThat(configuredViewportsDir.resolve("flight_form.qlc")).exists();
        assertThat(legacyUserDir.resolve("tools")).doesNotExist();
        assertThat(legacyUserDir.resolve("skills")).doesNotExist();
        assertThat(legacyUserDir.resolve("schedules")).doesNotExist();
        assertThat(legacyUserDir.resolve("viewports")).doesNotExist();
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

}
