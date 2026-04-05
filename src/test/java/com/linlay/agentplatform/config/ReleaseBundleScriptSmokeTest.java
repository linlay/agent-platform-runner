package com.linlay.agentplatform.config;

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
        String projectEnvExample = Files.readString(Path.of(".env.example"));
        String envExample = Files.readString(Path.of("scripts/release-assets/.env.example"));
        String startScript = Files.readString(Path.of("scripts/release-assets/start.sh"));
        String releaseCompose = Files.readString(Path.of("scripts/release-assets/compose.release.yml"));
        String projectCompose = Files.readString(Path.of("compose.yml"));

        assertThat(bundleReadme).contains("no precreated `runtime/`");
        assertThat(bundleReadme).contains("container environment variables");
        assertThat(bundleDoc).contains("脚本不会在 bundle 组装阶段预创建 `runtime/` 目录");
        assertThat(bundleDoc).contains("直接从进程环境读取宿主机路径");
        assertThat(bundleDoc).contains("`./start.sh` 时，脚本会对最终生效的这些目录逐一执行 `mkdir -p`");
        assertThat(bundleDoc).doesNotContain("空的运行目录骨架");
        assertThat(bundleDoc).doesNotContain("runtime 目录骨架");
        assertThat(projectReadme).contains("不再预创建 `runtime/` 目录骨架");
        assertThat(projectReadme).contains("会直接从当前进程环境变量读取宿主机 `*_DIR`");
        assertThat(projectReadme).contains("`./start.sh` 会按最终生效路径自动创建");
        assertThat(startScript).contains("REGISTRIES_DIR=\"${REGISTRIES_DIR:-$SCRIPT_DIR/runtime/registries}\"");
        assertThat(startScript).contains("ensure_dir \"$REGISTRIES_DIR/providers\"");
        assertThat(startScript).contains("ensure_dir \"$REGISTRIES_DIR/models\"");
        assertThat(startScript).contains("ensure_dir \"$REGISTRIES_DIR/mcp-servers\"");
        assertThat(startScript).contains("ensure_dir \"$REGISTRIES_DIR/viewport-servers\"");
        assertThat(startScript).contains("ensure_dir \"${AGENTS_DIR:-$SCRIPT_DIR/runtime/agents}\"");
        assertThat(startScript).contains("ensure_dir \"${SKILLS_MARKET_DIR:-$SCRIPT_DIR/runtime/skills-market}\"");
        assertThat(startScript).contains("ensure_dir \"${SCHEDULES_DIR:-$SCRIPT_DIR/runtime/schedules}\"");
        assertThat(bundleReadme).contains("container paths fixed under `/opt/*`");
        assertThat(bundleReadme).contains("default local public key file mode");
        assertThat(bundleDoc).contains("`SPRING_PROFILES_ACTIVE=docker`");
        assertThat(bundleDoc).contains("默认使用 `local-public-key.pem` 作为本地公钥文件");
        assertThat(projectReadme).contains("默认本地公钥文件是 `local-public-key.pem`");
        assertThat(projectReadme).contains("不再支持 `agent.auth.local-public-key`");
        assertThat(projectEnvExample).contains("AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE=local-public-key.pem");
        assertThat(envExample).contains("AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE=local-public-key.pem");
        assertThat(projectEnvExample).contains("REGISTRIES_DIR=./runtime/registries");
        assertThat(envExample).contains("REGISTRIES_DIR=./runtime/registries");
        assertThat(projectEnvExample).contains("AGENT_MEMORY_STORAGE_DIR=./runtime/memory");
        assertThat(envExample).contains("AGENT_MEMORY_STORAGE_DIR=./runtime/memory");
        assertThat(projectEnvExample).contains("AGENT_MEMORY_AUTO_REMEMBER_ENABLED=false");
        assertThat(envExample).contains("AGENT_MEMORY_AUTO_REMEMBER_ENABLED=false");
        assertThat(projectEnvExample).contains("AGENT_MEMORY_REMEMBER_MODEL_KEY=");
        assertThat(envExample).contains("AGENT_MEMORY_REMEMBER_MODEL_KEY=");
        assertThat(projectEnvExample).doesNotContain("AGENT_MEMORY_ENABLED");
        assertThat(envExample).doesNotContain("AGENT_MEMORY_ENABLED");
        assertThat(projectEnvExample).doesNotContain("AGENT_MEMORY_REMEMBER_ENABLED");
        assertThat(envExample).doesNotContain("AGENT_MEMORY_REMEMBER_ENABLED");
        assertThat(projectEnvExample).doesNotContain("MEMORY_REMEMBER_ENABLED");
        assertThat(envExample).doesNotContain("MEMORY_REMEMBER_ENABLED");
        assertThat(projectEnvExample).doesNotContain("MEMORY_DIR");
        assertThat(envExample).doesNotContain("MEMORY_DIR");
        assertThat(bundleDoc).contains("/opt/registries/{providers,models,mcp-servers,viewport-servers}");
        assertThat(releaseCompose).contains("SPRING_PROFILES_ACTIVE: docker");
        assertThat(releaseCompose).contains("target: /opt/agents");
        assertThat(releaseCompose).contains("source: ${REGISTRIES_DIR:-./runtime/registries}");
        assertThat(releaseCompose).contains("target: /opt/registries");
        assertThat(releaseCompose).doesNotContain("AGENTS_DIR: /opt");
        assertThat(releaseCompose).doesNotContain("target: /opt/runtime/agents");
        assertThat(releaseCompose).doesNotContain("SANDBOX_HOST_CHATS_DIR");
        assertThat(releaseCompose).doesNotContain("/tmp/runner-host.env");
        assertThat(projectCompose).contains("source: ${REGISTRIES_DIR:-./runtime/registries}");
        assertThat(projectCompose).contains("target: /opt/registries");
        assertThat(projectCompose).contains("SPRING_PROFILES_ACTIVE: docker");
        assertThat(projectCompose).doesNotContain("AGENTS_DIR: /opt");
        assertThat(projectCompose).doesNotContain("SANDBOX_HOST_CHATS_DIR");
        assertThat(projectCompose).doesNotContain("/tmp/runner-host.env");
    }
}
