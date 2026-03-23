package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;

class RuntimeResourceSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSyncOnlySystemSkillsAndSchedulesAndKeepExtraFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path schedulesDir = tempDir.resolve("schedules");
        Files.createDirectories(skillsDir);
        Files.createDirectories(schedulesDir);

        Path containerHubSkillFile = skillsDir.resolve("container_hub_validation").resolve("SKILL.md");
        Path extraSkillFile = skillsDir.resolve("custom_skill").resolve("SKILL.md");
        Path builtInSchedule = schedulesDir.resolve("builtin_placeholder_daily.yml");
        Path extraSchedule = schedulesDir.resolve("custom_schedule.yml");
        Files.createDirectories(containerHubSkillFile.getParent());
        Files.createDirectories(extraSkillFile.getParent());
        Files.writeString(containerHubSkillFile, "old-skill-content");
        Files.writeString(extraSkillFile, "custom skill content");
        Files.writeString(builtInSchedule, "old-schedule-content");
        Files.writeString(extraSchedule, "custom schedule content");

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                skillsDir,
                schedulesDir
        );
        service.syncRuntimeDirectories();
        service.syncRuntimeDirectories();

        String syncedSkill = Files.readString(containerHubSkillFile);
        String syncedSchedule = Files.readString(builtInSchedule);

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
        assertThat(Files.readString(extraSkillFile)).isEqualTo("custom skill content");
        assertThat(Files.readString(extraSchedule)).isEqualTo("custom schedule content");
    }

    @Test
    void shouldSyncToConfiguredDirectoriesInsteadOfUserDirRoot() throws Exception {
        Path configuredSkillsDir = tempDir.resolve("configured").resolve("skills");
        Path configuredSchedulesDir = tempDir.resolve("configured").resolve("schedules");
        Path legacyUserDir = tempDir.resolve("legacy-user-dir");

        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir(configuredSkillsDir.toString());
        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(configuredSchedulesDir.toString());

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", legacyUserDir.toString());
            RuntimeResourceSyncService service = new RuntimeResourceSyncService(
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

        assertThat(configuredSkillsDir.resolve("container_hub_validation").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("docx").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("pptx").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("screenshot").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSkillsDir.resolve("slack-gif-creator").resolve("SKILL.md")).doesNotExist();
        assertThat(configuredSchedulesDir.resolve("builtin_placeholder_daily.yml")).exists();
        assertThat(legacyUserDir.resolve("skills")).doesNotExist();
        assertThat(legacyUserDir.resolve("schedules")).doesNotExist();
    }

}
