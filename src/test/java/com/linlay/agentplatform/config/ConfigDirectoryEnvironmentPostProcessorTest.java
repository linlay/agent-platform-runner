package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigDirectoryEnvironmentPostProcessorTest {

    private final ConfigDirectoryEnvironmentPostProcessor processor = new ConfigDirectoryEnvironmentPostProcessor();

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadStructuredConfigFilesFromFixedConfigsDirectory() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("auth.yml"), """
                enabled: false
                local-public-key-file: local-public-key.pem
                """);
        Files.writeString(configsDir.resolve("bash.yml"), """
                allowed-commands: ls,pwd
                """);
        Files.writeString(configsDir.resolve("container-hub.yml"), """
                enabled: true
                default-environment-id: shell
                """);
        Files.writeString(configsDir.resolve("cors.yml"), """
                enabled: false
                allowed-origin-patterns:
                  - http://localhost:8081
                """);

        StandardEnvironment environment = environmentWithRequiredDirectories();
        withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)));

        assertThat(environment.getProperty("agent.auth.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.auth.local-public-key-file")).isEqualTo("local-public-key.pem");
        assertThat(environment.getProperty("agent.tools.bash.allowed-commands")).isEqualTo("ls,pwd");
        assertThat(environment.getProperty("agent.tools.container-hub.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("agent.tools.container-hub.default-environment-id")).isEqualTo("shell");
        assertThat(environment.getProperty("agent.cors.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.cors.allowed-origin-patterns")).isEqualTo("http://localhost:8081");
    }

    @Test
    void shouldFailFastWhenConfigUsesDeprecatedNestedKeys() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("auth.yml"), """
                agent:
                  auth:
                    enabled: false
                """);

        StandardEnvironment environment = environmentWithRequiredDirectories();

        assertThatThrownBy(() -> withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flat keys only");
    }

    @Test
    void shouldFailFastWhenContainerHubConfigUsesDeprecatedMountKeys() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("container-hub.yml"), """
                enabled: true
                mounts.user-dir: /tmp/user
                """);

        StandardEnvironment environment = environmentWithRequiredDirectories();

        assertThatThrownBy(() -> withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mounts.user-dir")
                .hasMessageContaining("ROOT_DIR");
    }

    @Test
    void shouldFailFastWhenDeprecatedDirectoryEnvVariableIsConfigured() {
        StandardEnvironment environment = environmentWithRequiredDirectories();
        environment.getPropertySources().addFirst(new MapPropertySource("deprecated", Map.of(
                "AGENT_AGENTS_EXTERNAL_DIR", tempDir.resolve("agents").toString()
        )));

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_AGENTS_EXTERNAL_DIR")
                .hasMessageContaining("AGENTS_DIR");
    }

    @Test
    void shouldFailFastWhenConfigsDirEnvVariableIsConfigured() {
        StandardEnvironment environment = environmentWithRequiredDirectories();
        environment.getPropertySources().addFirst(new MapPropertySource("deprecated", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, tempDir.resolve("configs").toString()
        )));

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIGS_DIR")
                .hasMessageContaining("fixed to './configs'")
                .hasMessageContaining("must not be overridden");
    }

    @Test
    void shouldIgnoreMissingFixedConfigDirectory() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        StandardEnvironment environment = environmentWithRequiredDirectories();

        withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)));

        assertThat(environment.getProperty("agent.auth.enabled")).isNull();
    }

    @Test
    void shouldIgnoreNestedProvidersDirectory() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Path providersDir = configsDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("a.yml"), """
                key: ignored
                baseUrl: https://api.one.example
                """);

        StandardEnvironment environment = environmentWithRequiredDirectories();
        withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)));

        assertThat(environment.getProperty("key")).isNull();
        assertThat(environment.getProperty("baseUrl")).isNull();
    }

    @Test
    void shouldNotRequireSkillsOrSchedulesDirectoryConfiguration() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        StandardEnvironment environment = new StandardEnvironment();

        withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)));

        assertThat(environment.getProperty("agent.skills.external-dir")).isNull();
        assertThat(environment.getProperty("agent.schedule.external-dir")).isNull();
    }

    @Test
    void shouldAllowExplicitPropertyOverridesForDirectories() throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("required", Map.of(
                "agent.skills.external-dir", tempDir.resolve("skills-market").toString(),
                "agent.schedule.external-dir", tempDir.resolve("schedules").toString()
        )));

        withUserDir(projectDir, () -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)));

        assertThat(environment.getProperty("agent.skills.external-dir")).isEqualTo(tempDir.resolve("skills-market").toString());
        assertThat(environment.getProperty("agent.schedule.external-dir")).isEqualTo(tempDir.resolve("schedules").toString());
    }

    private StandardEnvironment environmentWithRequiredDirectories() {
        return new StandardEnvironment();
    }

    private static void withUserDir(Path userDir, ThrowingRunnable action) throws Exception {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", userDir.toAbsolutePath().normalize().toString());
        try {
            action.run();
        } finally {
            restoreUserDir(previous);
        }
    }

    private static void restoreUserDir(String previous) {
        if (previous == null) {
            System.clearProperty("user.dir");
        } else {
            System.setProperty("user.dir", previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
