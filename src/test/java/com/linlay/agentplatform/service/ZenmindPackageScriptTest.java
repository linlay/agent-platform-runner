package com.linlay.agentplatform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZenmindPackageScriptTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPackageWorkspaceByExampleAndDemoNamingRules() throws Exception {
        Path scriptSource = Path.of("../.zenmind/package.sh").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(scriptSource), "external .zenmind package.sh is required for this test");

        Path workspace = tempDir.resolve(".zenmind");
        Files.createDirectories(workspace);
        Files.copy(scriptSource, workspace.resolve("package.sh"));

        writeFile(workspace.resolve("agents/normalAgent/agent.yml"), "key: normalAgent\n");
        writeFile(workspace.resolve("agents/demoModePlain/agent.yml"), "key: demoModePlain\n");
        writeFile(workspace.resolve("agents/template.example/agent.yml"), "key: template.example\n");
        writeFile(workspace.resolve("agents/skip.demo/agent.yml"), "key: skip.demo\n");

        writeFile(workspace.resolve("chats/plain.jsonl"), "{}\n");
        writeFile(workspace.resolve("chats/keep.example.jsonl"), "{}\n");
        writeFile(workspace.resolve("chats/skip.demo.jsonl"), "{}\n");
        writeFile(workspace.resolve("chats/keep.example/asset.txt"), "asset");
        writeFile(workspace.resolve("chats/plain/ignored.txt"), "ignored");

        writeFile(workspace.resolve("owner/OWNER.md"), "owner live");
        writeFile(workspace.resolve("owner.example/OWNER.md"), "owner example");

        writeFile(workspace.resolve("registries/providers/live.yml"), "key: live\n");
        writeFile(workspace.resolve("registries.example/providers/template.yml"), "key: template\n");

        writeFile(workspace.resolve("root/plain.txt"), "plain");
        writeFile(workspace.resolve("root/notes.example.txt"), "notes");
        writeFile(workspace.resolve("root/.hidden.example"), "hidden");
        writeFile(workspace.resolve("root/.config/ignored.txt"), "ignored");
        writeFile(workspace.resolve("root/.config.example/demo.txt"), "demo");

        writeFile(workspace.resolve("schedules/live.yml"), "name: live\n");
        writeFile(workspace.resolve("schedules/template.example.yml"), "name: template\n");
        writeFile(workspace.resolve("schedules/skip.demo.yml"), "name: skip\n");

        writeFile(workspace.resolve("skills-market/liveSkill/SKILL.md"), "live");
        writeFile(workspace.resolve("skills-market/templateSkill.example/SKILL.md"), "template");
        writeFile(workspace.resolve("skills-market/skipSkill.demo/SKILL.md"), "skip");

        writeFile(workspace.resolve("teams/a1b2c3d4e5f6.yml"), "name: live\n");
        writeFile(workspace.resolve("teams/b1b2c3d4e5f6.example.yml"), "name: template\n");
        writeFile(workspace.resolve("teams/c1b2c3d4e5f6.demo.yml"), "name: skip\n");

        writeFile(workspace.resolve("tools/custom.yml"), "name: custom\n");

        run(workspace, "bash", "./package.sh");

        Path archive = Files.list(workspace.resolve("dist")).findFirst().orElseThrow();
        List<String> entries = run(workspace, "tar", "-tzf", archive.toString());

        assertThat(entries).contains("zenmind-agents/agents/normalAgent/");
        assertThat(entries).contains("zenmind-agents/agents/demoModePlain/");
        assertThat(entries).contains("zenmind-agents/agents/template.example/");
        assertThat(entries).doesNotContain("zenmind-agents/agents/skip.demo/");

        assertThat(entries).contains("zenmind-agents/chats/keep.example.jsonl");
        assertThat(entries).contains("zenmind-agents/chats/keep.example/");
        assertThat(entries).doesNotContain("zenmind-agents/chats/plain.jsonl");
        assertThat(entries).doesNotContain("zenmind-agents/chats/skip.demo.jsonl");

        assertThat(entries).contains("zenmind-agents/owner/OWNER.md");
        assertThat(entries).doesNotContain("zenmind-agents/owner.example/OWNER.md");
        assertThat(entries).contains("zenmind-agents/registries/providers/template.yml");
        assertThat(entries).doesNotContain("zenmind-agents/registries.example/providers/template.yml");

        assertThat(entries).contains("zenmind-agents/root/notes.example.txt");
        assertThat(entries).contains("zenmind-agents/root/.hidden.example");
        assertThat(entries).contains("zenmind-agents/root/.config.example/");
        assertThat(entries).contains("zenmind-agents/root/.config.example/demo.txt");
        assertThat(entries).doesNotContain("zenmind-agents/root/plain.txt");
        assertThat(entries).doesNotContain("zenmind-agents/root/.config/ignored.txt");

        assertThat(entries).contains("zenmind-agents/schedules/live.yml");
        assertThat(entries).contains("zenmind-agents/schedules/template.example.yml");
        assertThat(entries).doesNotContain("zenmind-agents/schedules/skip.demo.yml");

        assertThat(entries).contains("zenmind-agents/skills-market/liveSkill/");
        assertThat(entries).contains("zenmind-agents/skills-market/templateSkill.example/");
        assertThat(entries).doesNotContain("zenmind-agents/skills-market/skipSkill.demo/");

        assertThat(entries).contains("zenmind-agents/teams/a1b2c3d4e5f6.yml");
        assertThat(entries).contains("zenmind-agents/teams/b1b2c3d4e5f6.example.yml");
        assertThat(entries).doesNotContain("zenmind-agents/teams/c1b2c3d4e5f6.demo.yml");

        assertThat(entries).noneMatch(entry -> entry.startsWith("zenmind-agents/tools"));
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private List<String> run(Path workdir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertThat(exitCode).as(output).isEqualTo(0);
        return output.lines().filter(line -> !line.isBlank()).toList();
    }
}
