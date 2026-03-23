package com.linlay.agentplatform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReleaseBundleScriptSmokeTest {

    @Test
    void shouldNotCopyExampleResourcesIntoReleaseBundle() throws IOException {
        String releaseScript = Files.readString(Path.of("scripts/release.sh"));

        assertThat(releaseScript).contains("\"$BUNDLE_ROOT/runtime/agents\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/tools\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/viewports\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/skills-market\"");
        assertThat(releaseScript).doesNotContain("\"$BUNDLE_ROOT/runtime/schedules\"");
        assertThat(releaseScript).doesNotContain("copy_example_dir");
        assertThat(releaseScript).doesNotContain("example/");
    }

    @Test
    void shouldDescribeRuntimeDirectoryAsExternalSkeleton() throws IOException {
        String bundleReadme = Files.readString(Path.of("scripts/release-assets/README.txt"));
        String bundleDoc = Files.readString(Path.of("docs/versioned-release-bundle.md"));

        assertThat(bundleReadme).contains("empty runtime directory skeleton");
        assertThat(bundleDoc).contains("不再从仓库内复制任何 starter / example 内容");
    }
}
