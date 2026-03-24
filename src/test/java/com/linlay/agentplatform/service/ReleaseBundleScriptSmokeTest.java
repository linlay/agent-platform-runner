package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReleaseBundleScriptSmokeTest {

    @Test
    void shouldNotPrecreateRuntimeDirectoriesInReleaseBundle() throws IOException {
        String releaseScript = Files.readString(Path.of("scripts/release.sh"));

        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/agents\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/skills-market\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/schedules\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/tools\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/viewports\"");
        assertThat(releaseScript).doesNotContain("copy_example_dir");
        assertThat(releaseScript).doesNotContain("example/");
    }

    @Test
    void shouldDescribeRuntimeDirectoriesAsStartupManaged() throws IOException {
        String bundleReadme = Files.readString(Path.of("scripts/release-assets/README.txt"));
        String bundleDoc = Files.readString(Path.of("docs/versioned-release-bundle.md"));
        String projectReadme = Files.readString(Path.of("README.md"));
        String startScript = Files.readString(Path.of("scripts/release-assets/start.sh"));
        String releaseCompose = Files.readString(Path.of("scripts/release-assets/compose.release.yml"));
        String projectCompose = Files.readString(Path.of("compose.yml"));

        assertThat(bundleReadme).contains("no precreated `runtime/`");
        assertThat(bundleReadme).contains("`/tmp/runner-host.env`");
        assertThat(bundleDoc).contains("脚本不会在 bundle 组装阶段预创建 `runtime/` 目录");
        assertThat(bundleDoc).contains("`/tmp/runner-host.env`");
        assertThat(bundleDoc).contains("`./start.sh` 时，脚本会对最终生效的这些目录逐一执行 `mkdir -p`");
        assertThat(bundleDoc).doesNotContain("空的运行目录骨架");
        assertThat(bundleDoc).doesNotContain("runtime 目录骨架");
        assertThat(projectReadme).contains("不再预创建 `runtime/` 目录骨架");
        assertThat(projectReadme).contains("`/tmp/runner-host.env`");
        assertThat(projectReadme).contains("`./start.sh` 会按最终生效路径自动创建");
        assertThat(startScript).contains("ensure_dir \"${AGENTS_DIR:-$SCRIPT_DIR/runtime/agents}\"");
        assertThat(startScript).contains("ensure_dir \"${SKILLS_MARKET_DIR:-$SCRIPT_DIR/runtime/skills-market}\"");
        assertThat(startScript).contains("ensure_dir \"${SCHEDULES_DIR:-$SCRIPT_DIR/runtime/schedules}\"");
        assertThat(bundleReadme).contains("container paths fixed under `/opt/*`");
        assertThat(bundleDoc).contains("`SPRING_PROFILES_ACTIVE=docker`");
        assertThat(bundleDoc).contains("容器内固定读取 `/opt/agents`");
        assertThat(releaseCompose).contains("target: /tmp/runner-host.env");
        assertThat(releaseCompose).contains("SANDBOX_HOST_DIRS_FILE: /tmp/runner-host.env");
        assertThat(releaseCompose).contains("SPRING_PROFILES_ACTIVE: docker");
        assertThat(releaseCompose).contains("target: /opt/agents");
        assertThat(releaseCompose).doesNotContain("AGENTS_DIR: /opt");
        assertThat(releaseCompose).doesNotContain("target: /opt/runtime/agents");
        assertThat(releaseCompose).doesNotContain("SANDBOX_HOST_CHATS_DIR");
        assertThat(projectCompose).contains("target: /tmp/runner-host.env");
        assertThat(projectCompose).contains("SANDBOX_HOST_DIRS_FILE: /tmp/runner-host.env");
        assertThat(projectCompose).contains("SPRING_PROFILES_ACTIVE: docker");
        assertThat(projectCompose).doesNotContain("AGENTS_DIR: /opt");
        assertThat(projectCompose).doesNotContain("SANDBOX_HOST_CHATS_DIR");
    }
}
