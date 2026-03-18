package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.config.DataProperties;
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
        ContainerHubMountResolver resolver = new ContainerHubMountResolver(properties, null, null);

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

        ContainerHubMountResolver resolver = new ContainerHubMountResolver(new ContainerHubToolProperties(), dataProperties, null);

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
        ContainerHubMountResolver resolver = new ContainerHubMountResolver(properties, null, null);

        assertThatThrownBy(() -> resolver.resolve(SandboxLevel.RUN, "chat-error"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to prepare run sandbox data mount directory")
                .hasMessageContaining(fileAsRoot.resolve("chat-error").toAbsolutePath().normalize().toString());
    }
}
