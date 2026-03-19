package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;

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

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-123");

        Path chatDir = dataRoot.resolve("chat-123");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/tmp");
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

        List<ContainerHubMountResolver.MountSpec> mounts = resolver.resolve(SandboxLevel.RUN, "chat-fallback");

        Path chatDir = dataRoot.resolve("chat-fallback");
        assertThat(Files.isDirectory(chatDir)).isTrue();
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::hostPath)
                .contains(chatDir.toAbsolutePath().normalize().toString());
        assertThat(mounts).extracting(ContainerHubMountResolver.MountSpec::containerPath)
                .contains("/tmp");
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

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-error"))
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

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-mount"))
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
        return new ContainerHubMountResolver(
                properties,
                dataProperties,
                skillProperties,
                new ToolProperties(),
                new AgentProperties(),
                new ModelProperties(),
                new ViewportProperties(),
                new TeamProperties(),
                new ScheduleProperties(),
                new McpProperties(),
                new ProviderProperties()
        );
    }
}
