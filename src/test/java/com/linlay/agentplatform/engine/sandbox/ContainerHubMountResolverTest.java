package com.linlay.agentplatform.engine.sandbox;

import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.config.properties.AgentProperties;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.config.properties.PanProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.properties.ToolProperties;
import com.linlay.agentplatform.config.properties.ViewportProperties;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.config.properties.ModelProperties;
import com.linlay.agentplatform.config.properties.ScheduleProperties;
import com.linlay.agentplatform.config.properties.SkillProperties;
import com.linlay.agentplatform.config.properties.TeamProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerHubMountResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void runLevelShouldCreateChatScopedDataDirectoryAndMountItToWorkspace() throws Exception {
        Path dataRoot = Files.createDirectories(tempDir.resolve("data-root"));
        RootProperties rootProperties = rootProperties(Files.createDirectories(tempDir.resolve("root")));
        SkillProperties skillProperties = skillProperties(Files.createDirectories(tempDir.resolve("skills")));
        PanProperties panProperties = panProperties(Files.createDirectories(tempDir.resolve("pan")));
        Path ownerDir = tempDir.resolve("owner");
        Path memoryRoot = tempDir.resolve("memory");

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
        Path flatAgentDir = tempDir.resolve("agents").resolve("flat-agent");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(Files.isDirectory(ownerDir)).isTrue();
        assertThat(Files.isDirectory(memoryRoot.resolve("flat-agent"))).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/workspace", "/root", "/skills", "/pan", "/agent", "/owner", "/memory");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(
                        chatDir.toAbsolutePath().normalize().toString(),
                        flatAgentDir.toAbsolutePath().normalize().toString(),
                        ownerDir.toAbsolutePath().normalize().toString(),
                        memoryRoot.resolve("flat-agent").toAbsolutePath().normalize().toString()
                );
        assertThat(mounts).filteredOn(mount -> "/workspace".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
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
                .contains("/workspace");
    }

    @Test
    void shouldMountDefaultDirectoriesAndAgentSelfDirectory() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path agentDir = Files.createDirectories(agentsDir.resolve("atlas"));
        Path agentSkillsDir = agentDir.resolve("skills");
        Path ownerDir = tempDir.resolve("owner");
        Path memoryRoot = tempDir.resolve("memory");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.AGENT, "chat", "atlas", List.of());

        assertThat(Files.isDirectory(dataDir.resolve("chat"))).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/workspace", "/root", "/skills", "/pan", "/agent", "/owner", "/memory");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(dataDir.toAbsolutePath().normalize().toString())
                .contains(agentSkillsDir.toAbsolutePath().normalize().toString())
                .contains(agentDir.toAbsolutePath().normalize().toString())
                .contains(ownerDir.toAbsolutePath().normalize().toString())
                .contains(memoryRoot.resolve("atlas").toAbsolutePath().normalize().toString());
        assertThat(Files.isDirectory(agentSkillsDir)).isTrue();
        assertThat(Files.isDirectory(ownerDir)).isTrue();
        assertThat(Files.isDirectory(memoryRoot.resolve("atlas"))).isTrue();
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(agentSkillsDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/agent".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
    }

    @Test
    void runLevelShouldMountAgentLocalSkillsWhenDirectoryAgentExists() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path agentDir = Files.createDirectories(agentsDir.resolve("atlas"));
        Path agentSkillsDir = agentDir.resolve("skills");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat", "atlas", List.of());

        assertThat(Files.isDirectory(agentSkillsDir)).isTrue();
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(agentSkillsDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void globalLevelShouldPrepareChatScopedSubdirectoryWhileMountingSharedWorkspace() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Files.createDirectories(agentsDir.resolve("chat-global-agent").resolve("skills"));
        Path ownerDir = tempDir.resolve("owner");
        Path memoryRoot = tempDir.resolve("memory");

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.GLOBAL, "chat-global", "flat-agent", List.of());

        assertThat(Files.isDirectory(dataDir.resolve("chat-global"))).isTrue();
        assertThat(Files.isDirectory(ownerDir)).isTrue();
        assertThat(Files.isDirectory(memoryRoot.resolve("flat-agent"))).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/workspace", "/root", "/skills", "/pan", "/agent", "/owner", "/memory");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(dataDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(skillsDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailWhenAgentDirectoryIsMissing() throws Exception {
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

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.AGENT, "chat", "missing-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for agent-self")
                .hasMessageContaining("containerPath=/agent")
                .hasMessageContaining("resolved=");
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
        Path viewportServersDir = Files.createDirectories(tempDir.resolve("viewport-servers"));
        Path ownerDir = Files.createDirectories(tempDir.resolve("owner"));
        Files.writeString(ownerDir.resolve("OWNER.md"), "owner");

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
                        new AgentDefinition.ExtraMount("models", null, null, MountAccessMode.RO),
                        new AgentDefinition.ExtraMount("tools", null, null, MountAccessMode.RW),
                        new AgentDefinition.ExtraMount("viewports", null, null, MountAccessMode.RO),
                        new AgentDefinition.ExtraMount("viewport-servers", null, null, MountAccessMode.RW),
                        new AgentDefinition.ExtraMount("chats", null, null, MountAccessMode.RO),
                        new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO),
                        new AgentDefinition.ExtraMount("owner", null, null, MountAccessMode.RO),
                        new AgentDefinition.ExtraMount("memory", null, null, MountAccessMode.RO)
                )
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/models", "/tools", "/viewports", "/viewport-servers", "/chats", "/skills-market", "/owner", "/memory");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(
                        modelsDir.toAbsolutePath().normalize().toString(),
                        toolsDir.toAbsolutePath().normalize().toString(),
                        viewportsDir.toAbsolutePath().normalize().toString(),
                        viewportServersDir.toAbsolutePath().normalize().toString(),
                        dataDir.toAbsolutePath().normalize().toString(),
                        skillsDir.toAbsolutePath().normalize().toString(),
                        ownerDir.toAbsolutePath().normalize().toString()
                );
        assertThat(mounts).filteredOn(mount -> "/models".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/tools".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).filteredOn(mount -> "/skills-market".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
    }

    @Test
    void shouldExposeGlobalSkillsMarketAlongsideAgentLocalSkills() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsMarketDir = Files.createDirectories(tempDir.resolve("skills-market"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path agentDir = Files.createDirectories(agentsDir.resolve("atlas"));
        Path agentSkillsDir = Files.createDirectories(agentDir.resolve("skills"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsMarketDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(tempDir.resolve("providers"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "atlas",
                List.of(new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO))
        );

        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(agentSkillsDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/skills-market".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(skillsMarketDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailWhenSkillsMarketIsRequestedButAgentDirectoryIsMissing() throws Exception {
        Path hostChatsDir = Files.createDirectories(tempDir.resolve("host-chats"));
        Path hostRootDir = Files.createDirectories(tempDir.resolve("host-root"));
        Path hostPanDir = Files.createDirectories(tempDir.resolve("host-pan"));
        Path hostSkillsDir = Files.createDirectories(tempDir.resolve("host-skills"));
        Path hostAgentsDir = Files.createDirectories(tempDir.resolve("host-agents"));
        Path accessChatsDir = Files.createDirectories(tempDir.resolve("access-chats"));
        Path accessRootDir = Files.createDirectories(tempDir.resolve("access-root"));
        Path accessPanDir = Files.createDirectories(tempDir.resolve("access-pan"));
        Path accessSkillsDir = Files.createDirectories(tempDir.resolve("access-skills"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(accessChatsDir),
                skillProperties(accessSkillsDir),
                rootProperties(accessRootDir),
                panProperties(accessPanDir),
                providerProperties(tempDir.resolve("providers")),
                hostRuntimeDirOverrides(Map.of(
                        "CHATS_DIR", hostChatsDir.toString(),
                        "ROOT_DIR", hostRootDir.toString(),
                        "PAN_DIR", hostPanDir.toString(),
                        "SKILLS_MARKET_DIR", hostSkillsDir.toString(),
                        "AGENTS_DIR", hostAgentsDir.toString()
                ))
        );

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.AGENT,
                "chat-missing-agent",
                "atlas",
                List.of(new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for agent-self")
                .hasMessageContaining("containerPath=/agent");
    }

    @Test
    void shouldFailWhenSkillsMarketPlatformMountSourceDoesNotExist() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = tempDir.resolve("missing-skills-market");
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Files.createDirectories(agentsDir.resolve("atlas").resolve("skills"));

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
                "atlas",
                List.of(new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extra-mount:skills-market")
                .hasMessageContaining("source does not exist")
                .hasMessageContaining("containerPath=/skills-market");
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
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "/datasets", MountAccessMode.RO))
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/datasets");
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(datasetDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/datasets".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(true);
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
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "datasets", MountAccessMode.RO))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destination must be an absolute path");
    }

    @Test
    void shouldOverrideDefaultMountModesWithoutAddingDuplicates() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Files.createDirectories(agentsDir.resolve("atlas"));
        Files.createDirectories(tempDir.resolve("owner"));
        Files.createDirectories(tempDir.resolve("memory"));

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
                "atlas",
                List.of(
                        new AgentDefinition.ExtraMount(null, null, "/skills", MountAccessMode.RW),
                        new AgentDefinition.ExtraMount(null, null, "/agent", MountAccessMode.RW),
                        new AgentDefinition.ExtraMount(null, null, "/owner", MountAccessMode.RW),
                        new AgentDefinition.ExtraMount(null, null, "/memory", MountAccessMode.RW)
                )
        );

        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath())).singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).filteredOn(mount -> "/agent".equals(mount.containerPath())).singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath())).singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath())).singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::readOnly)
                .isEqualTo(false);
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .doesNotHaveDuplicates();
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
                List.of(new AgentDefinition.ExtraMount(null, datasetDir.toString(), "/root", MountAccessMode.RW))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overriding a default mount")
                .hasMessageContaining("destination=/root");
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
                List.of(new AgentDefinition.ExtraMount("providers", null, null, MountAccessMode.RO))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extra-mount:providers")
                .hasMessageContaining("source does not exist")
                .hasMessageContaining("configured=" + providersDir);
    }

    @Test
    void shouldAutoCreateOwnerDefaultMountWhenSourceDoesNotExist() throws Exception {
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
                List.of()
        );

        assertThat(Files.isDirectory(tempDir.resolve("owner"))).isTrue();
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(tempDir.resolve("owner").toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailWhenCustomMountMissingSourceForNonDefaultDestination() throws Exception {
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

        assertThatThrownBy(() -> resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount(null, null, "/datasets", MountAccessMode.RO))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custom mount requires source + destination + mode");
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
                List.of(new AgentDefinition.ExtraMount("unknown", null, null, MountAccessMode.RO))
        );

        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .containsExactly("/workspace", "/root", "/skills", "/pan", "/agent", "/owner", "/memory");
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
                .hasMessageContaining("containerPath=/workspace")
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

    @Test
    void shouldPreferDotEnvHostPathsForSandboxMountSources() throws Exception {
        Path hostChatsDir = Files.createDirectories(tempDir.resolve("host-chats"));
        Path hostRootDir = Files.createDirectories(tempDir.resolve("host-root"));
        Path hostPanDir = Files.createDirectories(tempDir.resolve("host-pan"));
        Path hostSkillsDir = Files.createDirectories(tempDir.resolve("host-skills"));
        Path hostAgentsDir = Files.createDirectories(tempDir.resolve("host-agents"));
        Path hostAgentDir = Files.createDirectories(hostAgentsDir.resolve("atlas"));
        Path hostAgentSkillsDir = Files.createDirectories(hostAgentDir.resolve("skills"));
        Path hostOwnerDir = Files.createDirectories(tempDir.resolve("host-owner"));
        Path hostMemoryDir = Files.createDirectories(tempDir.resolve("host-memory"));
        Files.createDirectories(hostMemoryDir.resolve("atlas"));
        Path accessChatsDir = Files.createDirectories(tempDir.resolve("access-chats"));
        Path accessRootDir = Files.createDirectories(tempDir.resolve("access-root"));
        Path accessPanDir = Files.createDirectories(tempDir.resolve("access-pan"));
        Path accessSkillsDir = Files.createDirectories(tempDir.resolve("access-skills"));
        Files.createDirectories(tempDir.resolve("agents").resolve("atlas"));

        DataProperties dataProperties = dataProperties(accessChatsDir);
        SkillProperties skillProperties = skillProperties(accessSkillsDir);
        RootProperties rootProperties = rootProperties(accessRootDir);
        PanProperties panProperties = panProperties(accessPanDir);

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties,
                skillProperties,
                rootProperties,
                panProperties,
                providerProperties(tempDir.resolve("providers")),
                hostRuntimeDirOverrides(Map.of(
                        "CHATS_DIR", hostChatsDir.toString(),
                        "ROOT_DIR", hostRootDir.toString(),
                        "PAN_DIR", hostPanDir.toString(),
                        "SKILLS_MARKET_DIR", hostSkillsDir.toString(),
                        "AGENTS_DIR", hostAgentsDir.toString(),
                        "OWNER_DIR", hostOwnerDir.toString(),
                        "MEMORY_DIR", hostMemoryDir.toString()
                ))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-host", "atlas", List.of());

        assertThat(Files.isDirectory(accessChatsDir.resolve("chat-host"))).isTrue();
        assertThat(mounts).filteredOn(mount -> "/workspace".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostChatsDir.resolve("chat-host").toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/root".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostRootDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/pan".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostPanDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostAgentSkillsDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostOwnerDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostMemoryDir.resolve("atlas").toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldUseAgentsDirForAgentAndSkillsWhenOwnerDirOverrideAlsoExists() throws Exception {
        Path hostChatsDir = Files.createDirectories(tempDir.resolve("host-chats"));
        Path hostRootDir = Files.createDirectories(tempDir.resolve("host-root"));
        Path hostPanDir = Files.createDirectories(tempDir.resolve("host-pan"));
        Path hostSkillsDir = Files.createDirectories(tempDir.resolve("host-skills"));
        Path hostOwnerDir = Files.createDirectories(tempDir.resolve("host-owner"));
        Path hostAgentsDir = Files.createDirectories(tempDir.resolve("host-agents"));
        Path hostAgentDir = Files.createDirectories(hostAgentsDir.resolve("atlas"));
        Path hostAgentSkillsDir = Files.createDirectories(hostAgentDir.resolve("skills"));
        Path hostMemoryDir = Files.createDirectories(tempDir.resolve("host-memory"));
        Files.createDirectories(hostMemoryDir.resolve("atlas"));
        Path accessChatsDir = Files.createDirectories(tempDir.resolve("access-chats"));
        Path accessRootDir = Files.createDirectories(tempDir.resolve("access-root"));
        Path accessPanDir = Files.createDirectories(tempDir.resolve("access-pan"));
        Path accessSkillsDir = Files.createDirectories(tempDir.resolve("access-skills"));
        Files.createDirectories(tempDir.resolve("agents").resolve("atlas"));
        Files.createDirectories(tempDir.resolve("owner"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(accessChatsDir),
                skillProperties(accessSkillsDir),
                rootProperties(accessRootDir),
                panProperties(accessPanDir),
                providerProperties(tempDir.resolve("providers")),
                hostRuntimeDirOverrides(Map.of(
                        "CHATS_DIR", hostChatsDir.toString(),
                        "ROOT_DIR", hostRootDir.toString(),
                        "PAN_DIR", hostPanDir.toString(),
                        "SKILLS_MARKET_DIR", hostSkillsDir.toString(),
                        "OWNER_DIR", hostOwnerDir.toString(),
                        "AGENTS_DIR", hostAgentsDir.toString(),
                        "MEMORY_DIR", hostMemoryDir.toString()
                ))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat-owner-override",
                "atlas",
                List.of(new AgentDefinition.ExtraMount("owner", null, null, MountAccessMode.RO))
        );

        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostAgentSkillsDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/agent".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostAgentDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostOwnerDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo(hostMemoryDir.resolve("atlas").toAbsolutePath().normalize().toString());
    }

    @Test
    void shouldFailWhenAgentDirectoryIsMissingEvenIfOwnerOverrideExists() throws Exception {
        Path hostChatsDir = Files.createDirectories(tempDir.resolve("host-chats"));
        Path hostRootDir = Files.createDirectories(tempDir.resolve("host-root"));
        Path hostPanDir = Files.createDirectories(tempDir.resolve("host-pan"));
        Path hostSkillsDir = Files.createDirectories(tempDir.resolve("host-skills"));
        Path hostOwnerDir = Files.createDirectories(tempDir.resolve("host-owner"));
        Path hostAgentsDir = Files.createDirectories(tempDir.resolve("host-agents"));
        Path accessChatsDir = Files.createDirectories(tempDir.resolve("access-chats"));
        Path accessRootDir = Files.createDirectories(tempDir.resolve("access-root"));
        Path accessPanDir = Files.createDirectories(tempDir.resolve("access-pan"));
        Path accessSkillsDir = Files.createDirectories(tempDir.resolve("access-skills"));
        Files.createDirectories(tempDir.resolve("owner"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(accessChatsDir),
                skillProperties(accessSkillsDir),
                rootProperties(accessRootDir),
                panProperties(accessPanDir),
                providerProperties(tempDir.resolve("providers")),
                hostRuntimeDirOverrides(Map.of(
                        "CHATS_DIR", hostChatsDir.toString(),
                        "ROOT_DIR", hostRootDir.toString(),
                        "PAN_DIR", hostPanDir.toString(),
                        "SKILLS_MARKET_DIR", hostSkillsDir.toString(),
                        "OWNER_DIR", hostOwnerDir.toString(),
                        "AGENTS_DIR", hostAgentsDir.toString()
                ))
        );

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.AGENT, "chat-missing-agent", "atlas", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("container-hub mount validation failed for agent-self")
                .hasMessageContaining("containerPath=/agent");
    }

    @Test
    void shouldUseConfiguredAccessPathsWhenHostOverridesAreNotLocallyAccessible() throws Exception {
        Path accessChatsDir = Files.createDirectories(tempDir.resolve("access-chats"));
        Path accessRootDir = Files.createDirectories(tempDir.resolve("access-root"));
        Path accessPanDir = Files.createDirectories(tempDir.resolve("access-pan"));
        Path accessSkillsDir = Files.createDirectories(tempDir.resolve("access-skills"));
        Path accessAgentsDir = Files.createDirectories(tempDir.resolve("agents"));
        Path accessAgentDir = Files.createDirectories(accessAgentsDir.resolve("atlas"));
        Path accessAgentSkillsDir = accessAgentDir.resolve("skills");
        Path accessOwnerDir = Files.createDirectories(tempDir.resolve("owner"));
        Path accessMemoryRoot = Files.createDirectories(tempDir.resolve("memory"));
        Path accessMemoryDir = Files.createDirectories(accessMemoryRoot.resolve("atlas"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(accessChatsDir),
                skillProperties(accessSkillsDir),
                rootProperties(accessRootDir),
                panProperties(accessPanDir),
                providerProperties(tempDir.resolve("providers")),
                hostRuntimeDirOverrides(Map.of(
                        "CHATS_DIR", "/host/chats",
                        "ROOT_DIR", "/host/root",
                        "PAN_DIR", "/host/pan",
                        "SKILLS_MARKET_DIR", "/host/skills-market",
                        "AGENTS_DIR", "/host/agents",
                        "OWNER_DIR", "/host/owner",
                        "MEMORY_DIR", "/host/memory"
                ))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-split", "atlas", List.of());

        assertThat(Files.isDirectory(accessChatsDir.resolve("chat-split"))).isTrue();
        assertThat(Files.isDirectory(accessAgentSkillsDir)).isTrue();
        assertThat(mounts).filteredOn(mount -> "/workspace".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/chats/chat-split");
        assertThat(mounts).filteredOn(mount -> "/root".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/root");
        assertThat(mounts).filteredOn(mount -> "/pan".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/pan");
        assertThat(mounts).filteredOn(mount -> "/skills".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/agents/atlas/skills");
        assertThat(mounts).filteredOn(mount -> "/agent".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/agents/atlas");
        assertThat(mounts).filteredOn(mount -> "/owner".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/owner");
        assertThat(mounts).filteredOn(mount -> "/memory".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/memory/atlas");
    }

    @Test
    void shouldUseConfiguredAccessPathsForPlatformMountsWhenHostOverridesAreNotLocallyAccessible() throws Exception {
        Path rootDir = Files.createDirectories(tempDir.resolve("root"));
        Path skillsDir = Files.createDirectories(tempDir.resolve("skills"));
        Path panDir = Files.createDirectories(tempDir.resolve("pan"));
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Path providersDir = Files.createDirectories(tempDir.resolve("providers"));

        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(dataDir),
                skillProperties(skillsDir),
                rootProperties(rootDir),
                panProperties(panDir),
                providerProperties(providersDir),
                hostRuntimeDirOverrides(Map.of("REGISTRIES_DIR", "/host"))
        );

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(
                SandboxLevel.RUN,
                "chat",
                "flat-agent",
                List.of(new AgentDefinition.ExtraMount("providers", null, null, MountAccessMode.RO))
        );

        assertThat(mounts).filteredOn(mount -> "/providers".equals(mount.containerPath()))
                .singleElement()
                .extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .isEqualTo("/host/providers");
    }

    @Test
    void shouldFailWhenContainerPathDoesNotHaveDotEnvHostOverride() throws Exception {
        ContainerHubMountResolver resolver = containerHubMountResolver(
                new ContainerHubToolProperties(),
                dataProperties(Path.of("/opt/chats")),
                skillProperties(Files.createDirectories(tempDir.resolve("skills"))),
                rootProperties(Files.createDirectories(tempDir.resolve("root"))),
                panProperties(Files.createDirectories(tempDir.resolve("pan"))),
                providerProperties(tempDir.resolve("providers"))
        );

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-missing-host", "flat-agent", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing CHATS_DIR in " + RuntimeDirectoryHostPaths.DEFAULT_HOST_DIRS_FILE)
                .hasMessageContaining("configured=/opt/chats");
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            ProviderProperties providerProperties
    ) {
        return containerHubMountResolver(
                properties,
                dataProperties,
                skillProperties,
                rootProperties,
                panProperties,
                providerProperties,
                new RuntimeDirectoryHostPaths(Map.of())
        );
    }

    private ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            ProviderProperties providerProperties,
            RuntimeDirectoryHostPaths hostRuntimeDirOverrides
    ) {
        try {
            Files.createDirectories(tempDir.resolve("agents").resolve("flat-agent"));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare default test agent directory", ex);
        }
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir(tempDir.resolve("agents").toString());

        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setExternalDir(tempDir.resolve("tools").toString());

        ModelProperties modelProperties = new ModelProperties();
        modelProperties.setExternalDir(tempDir.resolve("models").toString());

        ViewportProperties viewportProperties = new ViewportProperties();
        viewportProperties.setExternalDir(tempDir.resolve("viewports").toString());

        ViewportServerProperties viewportServerProperties = new ViewportServerProperties();
        viewportServerProperties.getRegistry().setExternalDir(tempDir.resolve("viewport-servers").toString());

        TeamProperties teamProperties = new TeamProperties();
        teamProperties.setExternalDir(tempDir.resolve("teams").toString());

        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(tempDir.resolve("schedules").toString());

        McpProperties mcpProperties = new McpProperties();
        mcpProperties.getRegistry().setExternalDir(tempDir.resolve("mcp-servers").toString());

        return new ContainerHubMountResolver(
                new MountDirectoryConfig(
                        dataProperties == null ? null : dataProperties.getExternalDir(),
                        tempDir.resolve("memory").toString(),
                        rootProperties == null ? null : rootProperties.getExternalDir(),
                        panProperties == null ? null : panProperties.getExternalDir(),
                        skillProperties == null ? null : skillProperties.getExternalDir(),
                        agentProperties.getExternalDir(),
                        toolProperties.getExternalDir(),
                        registriesRoot(providerProperties),
                        viewportProperties.getExternalDir(),
                        teamProperties.getExternalDir(),
                        scheduleProperties.getExternalDir(),
                        tempDir.resolve("owner").toString()
                ),
                hostRuntimeDirOverrides
        );
    }

    private RuntimeDirectoryHostPaths hostRuntimeDirOverrides(Map<String, String> values) throws Exception {
        return new RuntimeDirectoryHostPaths(values, RuntimeDirectoryHostPaths.SYSTEM_ENVIRONMENT_SOURCE);
    }

    private String registriesRoot(ProviderProperties providerProperties) {
        if (providerProperties == null || providerProperties.getExternalDir() == null) {
            return tempDir.resolve("registries").toString();
        }
        Path providerDir = Path.of(providerProperties.getExternalDir()).normalize();
        Path parent = providerDir.getParent();
        return parent == null ? providerDir.toString() : parent.toString();
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
