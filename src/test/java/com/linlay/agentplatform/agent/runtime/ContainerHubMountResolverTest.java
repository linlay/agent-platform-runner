package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.PanProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.config.RootProperties;
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
        Path dataRoot = Files.createDirectories(tempDir.resolve("data-root"));
        RootProperties rootProperties = rootProperties(Files.createDirectories(tempDir.resolve("root")));
        SkillProperties skillProperties = skillProperties(Files.createDirectories(tempDir.resolve("skills")));
        PanProperties panProperties = panProperties(Files.createDirectories(tempDir.resolve("pan")));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataRoot),
                skillProperties,
                rootProperties,
                panProperties,
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-123", "flat-agent", List.of());

        Path chatDir = dataRoot.resolve("chat-123");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/tmp", "/root", "/skills", "/pan");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(chatDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void runLevelShouldFallbackToAgentDataExternalDirWhenMountDataDirIsNotConfigured() throws Exception {
        Path dataRoot = Files.createDirectories(tempDir.resolve("fallback-data"));
        RootProperties rootProperties = rootProperties(Files.createDirectories(tempDir.resolve("root")));
        SkillProperties skillProperties = skillProperties(Files.createDirectories(tempDir.resolve("skills")));
        PanProperties panProperties = panProperties(Files.createDirectories(tempDir.resolve("pan")));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataRoot),
                skillProperties,
                rootProperties,
                panProperties,
                providerProperties(tempDir.resolve("providers"))
        );

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
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path agentDir = Files.createDirectories(agentsDir.resolve("atlas"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.AGENT, "chat", "atlas", List.of());

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/root", "/skills", "/pan", "/agent");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(agentDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldSkipAgentSelfDirectoryForFlatAgent() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.AGENT, "chat", "flat-agent", List.of());

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/root", "/skills", "/pan");
    }

    @Test
    void shouldResolvePlatformExtraMounts() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
        Path toolsDir = Files.createDirectories(tempDir.resolve("tools"));
        Path viewportsDir = Files.createDirectories(tempDir.resolve("viewports"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

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
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

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
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

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
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path datasetDir = Files.createDirectories(tempDir.resolve("datasets"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "/root"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("containerPath conflicts with existing mount")
                .hasMessageContaining("containerPath=/root");
    }

    @Test
    void shouldFailWhenProvidersPlatformMountSourceDoesNotExist() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path providersDir = tempDir.resolve("providers");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(providersDir)
        );

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount("providers", null, null))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extra-mount:providers")
                .hasMessageContaining("source does not exist")
                .hasMessageContaining("configured=" + providersDir);
    }

    @Test
    void shouldSkipUnknownPlatformExtraMount() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount("unknown", null, null))
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/tmp", "/root", "/skills", "/pan");
    }

    @Test
    void runLevelShouldFailEarlyWhenChatScopedDataDirectoryCannotBeCreated() throws Exception {
        Path fileAsRoot = tempDir.resolve("not-a-directory");
        Files.writeString(fileAsRoot, "blocked");
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(fileAsRoot),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-error", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for data-dir")
                .hasMessageContaining("containerPath=/tmp")
                .hasMessageContaining(fileAsRoot.resolve("chat-error").toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailEarlyWhenRootDirDoesNotExist() throws Exception {
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path missingRootDir = tempDir.resolve("missing-root");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(missingRootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-mount", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for root-dir")
                .hasMessageContaining("configured=" + missingRootDir)
                .hasMessageContaining("containerPath=/root");
    }

    @Test
    void shouldFailEarlyWhenPanDirDoesNotExist() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path missingPanDir = tempDir.resolve("missing-pan");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(missingPanDir),
                providerProperties(tempDir.resolve("providers"))
        );

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-pan", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for pan-dir")
                .hasMessageContaining("configured=" + missingPanDir)
                .hasMessageContaining("containerPath=/pan");
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            ProviderProperties providerProperties
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
                dataProperties,
                rootProperties,
                panProperties,
                skillProperties,
                toolProperties,
                agentProperties,
                modelProperties,
                viewportProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties
        );
    }

    private DataProperties dataProperties(Path path) {
        DataProperties properties = new DataProperties();
        properties.setExternalDir(path.toString());
        return properties;
    }

    private SkillProperties skillProperties(Path path) {
        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(path.toString());
        return properties;
    }

    private RootProperties rootProperties(Path path) {
        RootProperties properties = new RootProperties();
        properties.setExternalDir(path.toString());
        return properties;
    }

    private PanProperties panProperties(Path path) {
        PanProperties properties = new PanProperties();
        properties.setExternalDir(path.toString());
        return properties;
    }

    private ProviderProperties providerProperties(Path path) {
        ProviderProperties properties = new ProviderProperties();
        properties.setExternalDir(path.toString());
        return properties;
    }
}
