package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.linlay.agentplatform.agent.AgentCatalogProperties;
import com.linlay.agentplatform.config.CapabilityCatalogProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ViewportCatalogProperties;
import com.linlay.agentplatform.model.ModelCatalogProperties;
import com.linlay.agentplatform.skill.SkillCatalogProperties;
import com.linlay.agentplatform.team.TeamCatalogProperties;

class RuntimeResourceSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldOverwriteBuiltInResourcesAndKeepExtraFiles() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Path toolsDir = tempDir.resolve("tools");
        Path viewportsDir = tempDir.resolve("viewports");
        Path skillsDir = tempDir.resolve("skills");
        Path teamsDir = tempDir.resolve("teams");
        Path modelsDir = tempDir.resolve("models");
        Path mcpServersDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(viewportsDir);
        Files.createDirectories(skillsDir);
        Files.createDirectories(teamsDir);
        Files.createDirectories(modelsDir);
        Files.createDirectories(mcpServersDir);

        Path modePlainAgent = agentsDir.resolve("demoModePlain.json");
        Path weatherTool = toolsDir.resolve("city_datetime.backend");
        Path skillScriptTool = toolsDir.resolve("_skill_run_script_.backend");
        Path weatherViewport = viewportsDir.resolve("show_weather_card.html");
        Path extraAgent = agentsDir.resolve("custom_agent.json");
        Path extraTool = toolsDir.resolve("custom.backend");
        Path extraViewport = viewportsDir.resolve("custom.html");
        Path qwenModel = modelsDir.resolve("bailian-qwen3-max.json");
        Path extraModel = modelsDir.resolve("custom-model.json");
        Path screenshotSkillFile = skillsDir.resolve("screenshot").resolve("SKILL.md");
        Path extraSkillFile = skillsDir.resolve("custom_skill").resolve("SKILL.md");
        Path defaultTeam = teamsDir.resolve("a1b2c3d4e5f6.json");
        Path extraTeam = teamsDir.resolve("ffeeddccbbaa.json");
        Path mockMcpServer = mcpServersDir.resolve("mock.json");

        Files.writeString(modePlainAgent, "old-agent-content");
        Files.writeString(weatherTool, "old-tool-content");
        Files.writeString(weatherViewport, "old-viewport-content");
        Files.writeString(extraAgent, "custom agent content");
        Files.writeString(extraTool, "custom tool content");
        Files.writeString(extraViewport, "custom viewport content");
        Files.writeString(qwenModel, "old-model-content");
        Files.writeString(extraModel, "custom model content");
        Files.createDirectories(screenshotSkillFile.getParent());
        Files.createDirectories(extraSkillFile.getParent());
        Files.writeString(screenshotSkillFile, "old-skill-content");
        Files.writeString(extraSkillFile, "custom skill content");
        Files.writeString(defaultTeam, "old-team-content");
        Files.writeString(extraTeam, "custom team content");

        RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                new PathMatchingResourcePatternResolver(),
                agentsDir,
                viewportsDir,
                toolsDir,
                skillsDir,
                teamsDir,
                modelsDir,
                mcpServersDir
        );
        service.syncRuntimeDirectories();
        service.syncRuntimeDirectories();

        String syncedTool = Files.readString(weatherTool);
        String syncedSkillScriptTool = Files.readString(skillScriptTool);
        String syncedViewport = Files.readString(weatherViewport);
        String syncedAgent = Files.readString(modePlainAgent);
        String syncedSkill = Files.readString(screenshotSkillFile);
        String syncedTeam = Files.readString(defaultTeam);
        String syncedModel = Files.readString(qwenModel);

        assertThat(syncedAgent).contains("\"mode\"").contains("\"ONESHOT\"");
        assertThat(syncedTool).contains("\"name\": \"city_datetime\"");
        assertThat(syncedSkillScriptTool).contains("\"name\": \"_skill_run_script_\"");
        assertThat(syncedViewport).contains("<title>天气卡片</title>");
        assertThat(syncedSkill).contains("name: \"screenshot\"");
        assertThat(syncedTeam).contains("\"name\": \"Default Team\"");
        assertThat(syncedModel).contains("\"key\": \"bailian-qwen3-max\"");
        assertThat(mockMcpServer).exists();
        assertThat(Files.readString(mockMcpServer)).contains("\"serverKey\": \"mock\"");
        assertThat(Files.readString(mockMcpServer)).contains("\"readTimeoutMs\": 15000");
        assertThat(skillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(skillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(Files.readString(extraAgent)).isEqualTo("custom agent content");
        assertThat(Files.readString(extraTool)).isEqualTo("custom tool content");
        assertThat(Files.readString(extraViewport)).isEqualTo("custom viewport content");
        assertThat(Files.readString(extraSkillFile)).isEqualTo("custom skill content");
        assertThat(Files.readString(extraTeam)).isEqualTo("custom team content");
        assertThat(Files.readString(extraModel)).isEqualTo("custom model content");
    }

    @Test
    void shouldSyncToConfiguredDirectoriesInsteadOfUserDirRoot() throws Exception {
        Path configuredAgentsDir = tempDir.resolve("configured").resolve("agents");
        Path configuredViewportsDir = tempDir.resolve("configured").resolve("viewports");
        Path configuredToolsDir = tempDir.resolve("configured").resolve("tools");
        Path configuredSkillsDir = tempDir.resolve("configured").resolve("skills");
        Path configuredTeamsDir = tempDir.resolve("configured").resolve("teams");
        Path configuredModelsDir = tempDir.resolve("configured").resolve("models");
        Path configuredMcpServersDir = tempDir.resolve("configured").resolve("mcp-servers");
        Path legacyUserDir = tempDir.resolve("legacy-user-dir");

        AgentCatalogProperties agentProperties = new AgentCatalogProperties();
        agentProperties.setExternalDir(configuredAgentsDir.toString());
        ViewportCatalogProperties viewportProperties = new ViewportCatalogProperties();
        viewportProperties.setExternalDir(configuredViewportsDir.toString());
        CapabilityCatalogProperties capabilityProperties = new CapabilityCatalogProperties();
        capabilityProperties.setToolsExternalDir(configuredToolsDir.toString());
        SkillCatalogProperties skillProperties = new SkillCatalogProperties();
        skillProperties.setExternalDir(configuredSkillsDir.toString());
        TeamCatalogProperties teamProperties = new TeamCatalogProperties();
        teamProperties.setExternalDir(configuredTeamsDir.toString());
        ModelCatalogProperties modelProperties = new ModelCatalogProperties();
        modelProperties.setExternalDir(configuredModelsDir.toString());
        McpProperties mcpProperties = new McpProperties();
        mcpProperties.getRegistry().setExternalDir(configuredMcpServersDir.toString());

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", legacyUserDir.toString());
            RuntimeResourceSyncService service = new RuntimeResourceSyncService(
                    agentProperties,
                    viewportProperties,
                    capabilityProperties,
                    mcpProperties,
                    skillProperties,
                    teamProperties,
                    modelProperties
            );
            service.syncRuntimeDirectories();
        } finally {
            if (originalUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", originalUserDir);
            }
        }

        assertThat(configuredAgentsDir.resolve("demoModePlain.json")).exists();
        assertThat(configuredViewportsDir.resolve("show_weather_card.html")).exists();
        assertThat(configuredToolsDir.resolve("city_datetime.backend")).exists();
        assertThat(configuredSkillsDir.resolve("screenshot").resolve("SKILL.md")).exists();
        assertThat(configuredTeamsDir.resolve("a1b2c3d4e5f6.json")).exists();
        assertThat(configuredModelsDir.resolve("bailian-qwen3-max.json")).exists();
        assertThat(configuredMcpServersDir).exists();
        assertThat(configuredMcpServersDir.resolve("mock.json")).exists();
        assertThat(configuredSkillsDir.resolve("math_basic").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("math_stats").resolve("SKILL.md")).exists();
        assertThat(configuredSkillsDir.resolve("text_utils").resolve("SKILL.md")).exists();
        assertThat(legacyUserDir.resolve("agents")).doesNotExist();
        assertThat(legacyUserDir.resolve("viewports")).doesNotExist();
        assertThat(legacyUserDir.resolve("tools")).doesNotExist();
        assertThat(legacyUserDir.resolve("skills")).doesNotExist();
        assertThat(legacyUserDir.resolve("teams")).doesNotExist();
        assertThat(legacyUserDir.resolve("models")).doesNotExist();
        assertThat(legacyUserDir.resolve("mcp-servers")).doesNotExist();
    }

    @Test
    void shouldKeepLegacyBashAliasWhenCanonicalToolExists() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Path toolsDir = tempDir.resolve("tools");
        Path viewportsDir = tempDir.resolve("viewports");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(viewportsDir);

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
                agentsDir,
                viewportsDir,
                toolsDir,
                tempDir.resolve("skills"),
                tempDir.resolve("teams"),
                tempDir.resolve("models"),
                tempDir.resolve("mcp-servers")
        );
        service.syncRuntimeDirectories();

        assertThat(toolsDir.resolve("_bash_.backend")).exists();
        assertThat(legacyBash).exists();
    }

    @Test
    void shouldKeepLegacySkillScriptAliasWhenCanonicalToolExists() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Path toolsDir = tempDir.resolve("tools");
        Path viewportsDir = tempDir.resolve("viewports");
        Files.createDirectories(agentsDir);
        Files.createDirectories(toolsDir);
        Files.createDirectories(viewportsDir);

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
                agentsDir,
                viewportsDir,
                toolsDir,
                tempDir.resolve("skills"),
                tempDir.resolve("teams"),
                tempDir.resolve("models"),
                tempDir.resolve("mcp-servers")
        );
        service.syncRuntimeDirectories();

        Path canonicalSkillScript = toolsDir.resolve("_skill_run_script_.backend");
        assertThat(canonicalSkillScript).exists();
        assertThat(Files.readString(canonicalSkillScript)).contains("\"name\": \"_skill_run_script_\"");
        assertThat(legacySkillScript).exists();
    }
}
