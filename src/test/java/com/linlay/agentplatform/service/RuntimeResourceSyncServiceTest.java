package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.linlay.agentplatform.config.CapabilityCatalogProperties;
import com.linlay.agentplatform.skill.SkillCatalogProperties;

class RuntimeResourceSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldOverwriteBuiltInResourcesAndKeepExtraFiles() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Path toolsDir = tempDir.resolve("tools");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(skillsDir);

        Path modePlainAgent = agentsDir.resolve("demoModePlain.json");
        Path weatherTool = toolsDir.resolve("city_datetime.backend");
        Path skillScriptTool = toolsDir.resolve("_skill_run_script_.backend");
        Path extraAgent = agentsDir.resolve("custom_agent.json");
        Path extraTool = toolsDir.resolve("custom.backend");
        Path mathBasicSkillFile = skillsDir.resolve("math_basic").resolve("SKILL.md");
        Path extraSkillFile = skillsDir.resolve("custom_skill").resolve("SKILL.md");

        Files.writeString(modePlainAgent, "old-agent-content");
        Files.writeString(weatherTool, "old-tool-content");
        Files.writeString(extraAgent, "custom agent content");
        Files.writeString(extraTool, "custom tool content");
        Files.createDirectories(mathBasicSkillFile.getParent());
        Files.createDirectories(extraSkillFile.getParent());
        Files.writeString(mathBasicSkillFile, "old-skill-content");
        Files.writeString(extraSkillFile, "custom skill content");

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                skillsDir
        );
        service.syncRuntimeDirectories();
        service.syncRuntimeDirectories();

        String syncedTool = Files.readString(weatherTool);
        String syncedSkillScriptTool = Files.readString(skillScriptTool);
        String syncedSkill = Files.readString(mathBasicSkillFile);

        assertThat(syncedTool).contains("\"name\": \"city_datetime\"");
        assertThat(syncedSkillScriptTool).contains("\"name\": \"_skill_run_script_\"");
        assertThat(syncedSkill).contains("name: \"math_basic\"");
        assertThat(skillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(Files.readString(modePlainAgent)).isEqualTo("old-agent-content");
        assertThat(agentsDir.resolve("demoAction.json")).doesNotExist();
        assertThat(Files.readString(extraAgent)).isEqualTo("custom agent content");
        assertThat(Files.readString(extraTool)).isEqualTo("custom tool content");
        assertThat(Files.readString(extraSkillFile)).isEqualTo("custom skill content");
    }

    @Test
    void shouldSyncToConfiguredDirectoriesInsteadOfUserDirRoot() throws Exception {
        Path configuredToolsDir = tempDir.resolve("configured").resolve("tools");
        Path configuredSkillsDir = tempDir.resolve("configured").resolve("skills");
        Path legacyUserDir = tempDir.resolve("legacy-user-dir");

        CapabilityCatalogProperties capabilityProperties = new CapabilityCatalogProperties();
        capabilityProperties.setToolsExternalDir(configuredToolsDir.toString());
        SkillCatalogProperties skillProperties = new SkillCatalogProperties();
        skillProperties.setExternalDir(configuredSkillsDir.toString());

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", legacyUserDir.toString());
            RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                    capabilityProperties,
                    skillProperties
            );
            service.syncRuntimeDirectories();
        } finally {
            if (originalUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", originalUserDir);
            }
        }

        assertThat(configuredToolsDir.resolve("city_datetime.backend")).exists();
        assertThat(configuredSkillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(legacyUserDir.resolve("tools")).doesNotExist();
        assertThat(legacyUserDir.resolve("skills")).doesNotExist();
    }

    @Test
    void shouldKeepLegacyBashAliasWhenCanonicalToolExists() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Path legacyBash = toolsDir.resolve("bash.backend");
        Files.writeString(legacyBash, """
                {
                  "tools": [
                    {
                      "type": "function",
                      "name": "_bash_",
                      "description": "legacy alias"
                    }
                  ]
                }
                """);

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                tempDir.resolve("skills")
        );
        service.syncRuntimeDirectories();

        assertThat(toolsDir.resolve("_bash_.backend")).exists();
        assertThat(legacyBash).exists();
    }

    @Test
    void shouldKeepLegacySkillScriptAliasWhenCanonicalToolExists() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Path legacySkillScript = toolsDir.resolve("skill_script_run.backend");
        Files.writeString(legacySkillScript, """
                {
                  "tools": [
                    {
                      "type": "function",
                      "name": "skill_script_run",
                      "description": "legacy alias"
                    }
                  ]
                }
                """);

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                toolsDir,
                tempDir.resolve("skills")
        );
        service.syncRuntimeDirectories();

        Path canonicalSkillScript = toolsDir.resolve("_skill_run_script_.backend");
        assertThat(canonicalSkillScript).exists();
        assertThat(Files.readString(canonicalSkillScript)).contains("\"name\": \"_skill_run_script_\"");
        assertThat(legacySkillScript).exists();
    }
}
