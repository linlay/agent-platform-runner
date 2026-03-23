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
        assertThat(bundleReadme).contains("mounts `.env` into the runner container");
        assertThat(bundleDoc).contains("脚本不会在 bundle 组装阶段预创建 `runtime/` 目录");
        assertThat(bundleDoc).contains("`/opt/configs/.env`");
        assertThat(bundleDoc).contains("`./start.sh` 时，脚本会对最终生效的这些目录逐一执行 `mkdir -p`");
        assertThat(bundleDoc).doesNotContain("空的运行目录骨架");
        assertThat(bundleDoc).doesNotContain("runtime 目录骨架");
        assertThat(projectReadme).contains("不再预创建 `runtime/` 目录骨架");
        assertThat(projectReadme).contains("`./.env -> /opt/configs/.env`");
        assertThat(projectReadme).contains("`./start.sh` 会按最终生效路径自动创建");
        assertThat(startScript).contains("ensure_dir \"${AGENTS_DIR:-$SCRIPT_DIR/runtime/agents}\"");
        assertThat(startScript).contains("ensure_dir \"${SKILLS_MARKET_DIR:-$SCRIPT_DIR/runtime/skills-market}\"");
        assertThat(startScript).contains("ensure_dir \"${SCHEDULES_DIR:-$SCRIPT_DIR/runtime/schedules}\"");
        assertThat(releaseCompose).contains("source: ./.env");
        assertThat(releaseCompose).contains("target: /opt/configs/.env");
        assertThat(projectCompose).contains("source: ./.env");
        assertThat(projectCompose).contains("target: /opt/configs/.env");
    }
}
