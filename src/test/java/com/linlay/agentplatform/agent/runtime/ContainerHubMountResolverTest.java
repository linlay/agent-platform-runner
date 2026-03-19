package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerHubMountResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void runLevelShouldCreateChatScopedDataDirectoryAndMountItToTmp() throws Exception {
        Path dataRoot = tempDir.resolve("data-root");
        Files.createDirectories(dataRoot);

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir(dataRoot.toString());
        properties.getMounts().setUserDir("");
        properties.getMounts().setSkillsDir("");
        properties.getMounts().setPanDir("");
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-123", "flat-agent", List.of());

        Path chatDir = dataRoot.resolve("chat-123");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(chatDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void runLevelShouldFallbackToAgentDataExternalDirWhenMountDataDirIsNotConfigured() throws Exception {
        Path dataRoot = tempDir.resolve("fallback-data");
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir(dataRoot.toString());
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setUserDir("");
        properties.getMounts().setSkillsDir("");
        properties.getMounts().setPanDir("");

        ContainerHubMountResolver resolver = containerHubMountResolver(properties, dataProperties, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-fallback", "flat-agent", List.of());

        Path chatDir = dataRoot.resolve("chat-fallback");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(chatDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/tmp");
    }

    @Test
    void shouldMountDefaultDirectoriesAndAgentSelfDirectory() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path agentDir = Files.createDirectories(agentsDir.resolve("atlas"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.AGENT, "chat", "atlas", List.of());

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/home", "/skills", "/pan", "/agent");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(agentDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldSkipAgentSelfDirectoryForFlatAgent() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.AGENT, "chat", "flat-agent", List.of());

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/home", "/skills", "/pan");
    }

    @Test
    void shouldResolvePlatformExtraMounts() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
        Path toolsDir = Files.createDirectories(tempDir.resolve("tools"));
        Path viewportsDir = Files.createDirectories(tempDir.resolve("viewports"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(
                        new AgentDefinition.ExtraMount("models", null, null),
                        new AgentDefinition.ExtraMount("tools", null, null),
                        new AgentDefinition.ExtraMount("viewports", null, null)
                )
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/models", "/tools", "/viewports");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(
                        modelsDir.toAbsolutePath().normalize().toString(),
                        toolsDir.toAbsolutePath().normalize().toString(),
                        viewportsDir.toAbsolutePath().normalize().toString()
                );
    }

    @Test
    void shouldAddCustomExtraMount() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "/datasets"))
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/datasets");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(datasetDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailWhenCustomExtraMountDestinationIsRelative() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "datasets"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destination must be an absolute path");
    }

    @Test
    void shouldFailWhenExtraMountDestinationConflicts() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "/home"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("containerPath conflicts with existing mount")
                .hasMessageContaining("containerPath=/home");
    }

    @Test
    void shouldFailWhenProvidersPlatformMountIsRequestedWithoutConfiguredSource() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount("providers", null, null))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extra-mount:providers")
                .hasMessageContaining("source is not configured");
    }

    @Test
    void shouldSkipUnknownPlatformExtraMount() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(userDir.toString());
        properties.getMounts().setSkillsDir(skillsDir.toString());
        properties.getMounts().setPanDir(panDir.toString());
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount("unknown", null, null))
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/home", "/skills", "/pan");
    }

    @Test
    void runLevelShouldFailEarlyWhenChatScopedDataDirectoryCannotBeCreated() throws Exception {
        Path fileAsRoot = tempDir.resolve("not-a-directory");
        Files.writeString(fileAsRoot, "blocked");

        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir(fileAsRoot.toString());
        properties.getMounts().setUserDir("");
        properties.getMounts().setSkillsDir("");
        properties.getMounts().setPanDir("");
        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-error", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for data-dir")
                .hasMessageContaining("containerPath=/tmp")
                .hasMessageContaining(fileAsRoot.resolve("chat-error").toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailEarlyWhenConfiguredUserDirDoesNotExist() {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.getMounts().setDataDir("");
        properties.getMounts().setUserDir(tempDir.resolve("missing-home").toString());
        properties.getMounts().setSkillsDir("");
        properties.getMounts().setPanDir("");

        ContainerHubMountResolver resolver = containerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-mount", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for user-dir")
                .hasMessageContaining("configured=" + tempDir.resolve("missing-home"))
                .hasMessageContaining("containerPath=/home");
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties
    ) {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir(tempDir.resolve("agents").toString());

        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setExternalDir(tempDir.resolve("tools").toString());

        ModelProperties modelProperties = new ModelProperties();
        modelProperties.setExternalDir(tempDir.resolve("models").toString());

        ViewportProperties viewportProperties = new ViewportProperties();
        viewportProperties.setExternalDir(tempDir.resolve("viewports").toString());

        TeamProperties teamProperties = new TeamProperties();
        teamProperties.setExternalDir(tempDir.resolve("teams").toString());

        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(tempDir.resolve("schedules").toString());

        McpProperties mcpProperties = new McpProperties();
        mcpProperties.getRegistry().setExternalDir(tempDir.resolve("mcp-servers").toString());

        return new ContainerHubMountResolver(
                properties,
                dataProperties,
                skillProperties,
                toolProperties,
                agentProperties,
                modelProperties,
                viewportProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                new ProviderProperties()
        );
    }
}
