package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SystemBashAdvancedTest {

    @Test
    void shouldSupportPipelineWhenShellFeaturesEnabled(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "foo\nbar\n");
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("cat", "rg"),
                Set.of("cat"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "cat a.txt | rg foo"));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).contains("foo");
    }

    @Test
    void shouldSupportHereDocWhenShellFeaturesEnabled(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("cat"),
                Set.of("cat"),
                true,
                "bash",
                10_000,
                16_000
        );

        String command = """
                cat <<'EOF' > out.txt
                hello-heredoc
                EOF
                cat out.txt
                """;

        JsonNode result = bash.invoke(Map.of("command", command));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).contains("hello-heredoc");
    }

    @Test
    void shouldSupportLogicalOperatorsWhenShellFeaturesEnabled(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("cat", "echo"),
                Set.of("cat"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "cat missing.txt || echo fallback"));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("fallback");
    }

    @Test
    void shouldRejectDisallowedCommandInsideCommandSubstitution(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("echo"),
                Set.of("echo"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "echo $(uname -s)"));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Command not allowed: uname");
    }

    @Test
    void shouldRejectRedirectOutsideAllowedPaths(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(),
                Set.of("echo"),
                Set.of("echo"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "echo hello > /tmp/outside_bash_tool_test.txt"));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories");
    }

    @Test
    void shouldAllowRedirectToDevNullInShellMode(@TempDir Path tempDir) throws IOException {
        Path schedulesDir = tempDir.resolve("schedules");
        Files.createDirectories(schedulesDir);
        Files.writeString(schedulesDir.resolve("demo.json"), "{}");

        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(),
                Set.of("ls", "head"),
                Set.of("ls", "head"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "ls -la schedules/ 2>/dev/null | head -20"));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).contains("demo.json");
        assertThat(result.asText()).doesNotContain("Path not allowed outside authorized directories: /dev/null");
    }

    @Test
    void shouldRejectUnsupportedSourceSyntax(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(),
                Set.of("echo", "source"),
                Set.of("echo"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "source script.sh || echo fallback"));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Unsupported syntax for _bash_: source");
    }

    @Test
    void shouldKeepStrictModeWhenShellFeaturesDisabled(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "foo\nbar\n");
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(),
                Set.of("cat", "rg"),
                Set.of("cat"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "cat a.txt | rg foo"));

        assertThat(result.asText()).contains("exitCode: 1");
        assertThat(result.asText()).contains("No such file or directory");
        assertThat(result.asText()).contains("mode: strict");
    }

    @Test
    void shouldAllowGitSubcommandAfterPathOptionInStrictMode(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(repo),
                Set.of("git"),
                Set.of("git"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "git -C repo status"));

        assertThat(result.asText()).contains("mode: strict");
        assertThat(result.asText()).doesNotContain("Path not allowed outside authorized directories: status");
    }

    @Test
    void shouldAllowGitSubcommandAfterPathOptionInShellMode(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(repo),
                Set.of("git", "echo"),
                Set.of("git"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "git -C repo status || echo fallback"));

        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).doesNotContain("Path not allowed outside authorized directories: status");
    }

    @Test
    void shouldRejectGitPathOptionOutsideAllowedRoots(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(repo),
                Set.of("git"),
                Set.of("git"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "git -C ../outside status"));

        assertThat(result.asText()).contains("mode: strict");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories: ../outside");
    }

    @Test
    void shouldAllowGitPathOptionOutsideAllowedRootsWhenBypassEnabled(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(repo),
                Set.of("git"),
                Set.of("git"),
                Set.of("git"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "git -C ../outside status"));

        assertThat(result.asText()).contains("mode: strict");
        assertThat(result.asText()).doesNotContain("Path not allowed outside authorized directories: ../outside");
    }

    @Test
    void shouldAllowCurlRedirectOutsideAllowedPathsWhenBypassEnabled(@TempDir Path tempDir) throws IOException {
        Path outsideFile = tempDir.getParent().resolve("outside-bypass-curl.txt");
        Files.deleteIfExists(outsideFile);
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("curl"),
                Set.of("curl"),
                Set.of("curl"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "curl --version > ../outside-bypass-curl.txt"));

        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).doesNotContain("Path not allowed outside authorized directories");
        assertThat(Files.exists(outsideFile)).isTrue();
    }

    @Test
    void shouldRejectCurlRedirectOutsideAllowedPathsWhenBypassDisabled(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("curl"),
                Set.of("curl"),
                true,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "curl --version > ../outside-bypass-curl.txt"));

        assertThat(result.asText()).contains("mode: shell");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories: ../outside-bypass-curl.txt");
    }

    @Test
    void shouldKeepNonBypassCommandPathChecksWhenBypassConfigured(@TempDir Path tempDir) {
        SystemBash bash = TestSystemBashFactory.bash(
                tempDir,
                List.of(tempDir),
                Set.of("cat", "curl"),
                Set.of("cat", "curl"),
                Set.of("curl"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "cat ../outside.txt"));

        assertThat(result.asText()).contains("mode: strict");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories: ../outside.txt");
    }

    @Test
    void shouldRejectRelativeSchedulesPathWhenWorkingDirectoryIsOutsideProject(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path schedulesDir = projectDir.resolve("schedules");
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(schedulesDir);
        Files.createDirectories(outsideDir);
        Files.writeString(schedulesDir.resolve("demo.json"), "{}");

        SystemBash bash = TestSystemBashFactory.bash(
                outsideDir,
                List.of(projectDir),
                Set.of("ls"),
                Set.of("ls"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "ls schedules/"));

        assertThat(result.asText()).contains("exitCode: 1");
        assertThat(result.asText()).contains("No such file or directory");
    }

    @Test
    void shouldAllowRelativeSchedulesPathWhenWorkingDirectoryMatchesProject(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path schedulesDir = projectDir.resolve("schedules");
        Files.createDirectories(schedulesDir);
        Files.writeString(schedulesDir.resolve("demo.json"), "{}");

        SystemBash bash = TestSystemBashFactory.bash(
                projectDir,
                List.of(projectDir, schedulesDir),
                Set.of("ls"),
                Set.of("ls"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "ls schedules/"));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("demo.json");
    }

    @Test
    void shouldAllowSchedulesUnderWorkingDirectoryWithoutExplicitAllowedPath(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path schedulesDir = projectDir.resolve("schedules");
        Files.createDirectories(schedulesDir);
        Files.writeString(schedulesDir.resolve("demo.json"), "{}");

        SystemBash bash = TestSystemBashFactory.bash(
                projectDir,
                List.of(),
                Set.of("ls"),
                Set.of("ls"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "ls ./schedules/"));

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("demo.json");
    }

    @Test
    void shouldRejectPathOutsideWorkingDirectoryWhenNoExtraAllowedRoot(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project");
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(projectDir);
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("demo.txt"), "outside");

        SystemBash bash = TestSystemBashFactory.bash(
                projectDir,
                List.of(),
                Set.of("cat"),
                Set.of("cat"),
                false,
                "bash",
                10_000,
                16_000
        );

        JsonNode result = bash.invoke(Map.of("command", "cat ../outside/demo.txt"));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories: ../outside/demo.txt");
    }
}
