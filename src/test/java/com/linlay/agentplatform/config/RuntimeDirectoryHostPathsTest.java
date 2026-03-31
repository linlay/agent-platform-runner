package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDirectoryHostPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreferRuntimeDirectoryEnvironmentValues() throws Exception {
        Path file = tempDir.resolve("runner-host.env");
        Files.writeString(file, "CHATS_DIR=/file/chats\n");

        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load(Map.of(
                RuntimeDirectoryHostPaths.HOST_DIRS_FILE_ENV, file.toString(),
                "REGISTRIES_DIR", "/env/registries",
                "AGENTS_DIR", "/env/agents"
        ));

        assertThat(hostPaths.sourcePath()).isEqualTo(RuntimeDirectoryHostPaths.SYSTEM_ENVIRONMENT_SOURCE);
        assertThat(hostPaths.get("REGISTRIES_DIR")).isEqualTo("/env/registries");
        assertThat(hostPaths.get("AGENTS_DIR")).isEqualTo("/env/agents");
        assertThat(hostPaths.get("CHATS_DIR")).isNull();
    }

    @Test
    void shouldFallbackToHostDirsFileWhenNoRuntimeDirectoryEnvironmentValuesExist() throws Exception {
        Path file = tempDir.resolve("runner-host.env");
        Files.writeString(file, """
                REGISTRIES_DIR=/file/registries
                CHATS_DIR=/file/chats
                """);

        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load(Map.of(
                RuntimeDirectoryHostPaths.HOST_DIRS_FILE_ENV, file.toString(),
                "AGENT_AUTH_ENABLED", "true"
        ));

        assertThat(hostPaths.sourcePath()).isEqualTo(file.toString());
        assertThat(hostPaths.get("REGISTRIES_DIR")).isEqualTo("/file/registries");
        assertThat(hostPaths.get("CHATS_DIR")).isEqualTo("/file/chats");
    }

    @Test
    void shouldUseWholeSourceSwitchingWhenAnyRuntimeDirectoryEnvironmentValueExists() throws Exception {
        Path file = tempDir.resolve("runner-host.env");
        Files.writeString(file, """
                REGISTRIES_DIR=/file/registries
                CHATS_DIR=/file/chats
                """);

        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load(Map.of(
                RuntimeDirectoryHostPaths.HOST_DIRS_FILE_ENV, file.toString(),
                "AGENTS_DIR", "/env/agents"
        ));

        assertThat(hostPaths.sourcePath()).isEqualTo(RuntimeDirectoryHostPaths.SYSTEM_ENVIRONMENT_SOURCE);
        assertThat(hostPaths.get("AGENTS_DIR")).isEqualTo("/env/agents");
        assertThat(hostPaths.get("REGISTRIES_DIR")).isNull();
        assertThat(hostPaths.get("CHATS_DIR")).isNull();
    }

    @Test
    void shouldIgnoreLegacyRegistryFileEntries() throws Exception {
        Path file = tempDir.resolve("runner-host.env");
        Files.writeString(file, """
                PROVIDERS_DIR=/legacy/registries/providers
                MODELS_DIR=/legacy/registries/models
                MCP_SERVERS_DIR=/legacy/registries/mcp-servers
                VIEWPORT_SERVERS_DIR=/legacy/registries/viewport-servers
                """);

        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load(Map.of(
                RuntimeDirectoryHostPaths.HOST_DIRS_FILE_ENV, file.toString()
        ));

        assertThat(hostPaths.sourcePath()).isEqualTo(file.toString());
        assertThat(hostPaths.get("REGISTRIES_DIR")).isNull();
        assertThat(hostPaths.get("PROVIDERS_DIR")).isNull();
    }
}
