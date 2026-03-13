package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.tool.SystemBash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BashToolPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BashToolConfiguration.class);

    @Test
    void indexedPropertiesShouldBindAndAllowConfiguredPaths(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello-indexed");

        contextRunner
                .withPropertyValues(
                        "agent.tools.bash.working-directory=" + tempDir,
                        "agent.tools.bash.allowed-paths[0]=" + tempDir,
                        "agent.tools.bash.allowed-commands[0]=cat"
                )
                .run(context -> {
                    BashToolProperties properties = context.getBean(BashToolProperties.class);
                    assertThat(properties.getAllowedCommands()).containsExactly("cat");
                    assertThat(properties.getAllowedPaths()).containsExactly(tempDir.toString());

                    SystemBash bash = context.getBean(SystemBash.class);
                    JsonNode result = bash.invoke(Map.of("command", "cat demo.txt"));
                    assertThat(result.asText()).contains("exitCode: 0");
                    assertThat(result.asText()).contains("hello-indexed");
                });
    }

    @Test
    void commaSeparatedPropertiesShouldBindAndAllowConfiguredPaths(@TempDir Path tempDir) throws IOException {
        Path workingDir = tempDir.resolve("workspace");
        Path externalDir = tempDir.resolve("external");
        Files.createDirectories(workingDir);
        Files.createDirectories(externalDir);
        Path externalFile = externalDir.resolve("demo.txt");
        Files.writeString(externalFile, "hello-comma");

        contextRunner
                .withPropertyValues(
                        "agent.tools.bash.working-directory=" + workingDir,
                        "agent.tools.bash.allowed-paths=" + workingDir + "," + externalDir,
                        "agent.tools.bash.allowed-commands=cat,echo",
                        "agent.tools.bash.path-checked-commands=cat,git",
                        "agent.tools.bash.path-check-bypass-commands=git,curl"
                )
                .run(context -> {
                    BashToolProperties properties = context.getBean(BashToolProperties.class);
                    assertThat(properties.getAllowedCommands()).contains("cat", "echo");
                    assertThat(properties.getAllowedPaths()).contains(workingDir.toString(), externalDir.toString());
                    assertThat(properties.getPathCheckBypassCommands()).contains("git", "curl");

                    SystemBash bash = context.getBean(SystemBash.class);
                    JsonNode catResult = bash.invoke(Map.of("command", "cat " + externalFile));
                    JsonNode echoResult = bash.invoke(Map.of("command", "echo ok"));

                    assertThat(catResult.asText()).contains("exitCode: 0");
                    assertThat(catResult.asText()).contains("hello-comma");
                    assertThat(echoResult.asText()).contains("exitCode: 0");
                    assertThat(echoResult.asText()).contains("ok");
                });
    }

    @Test
    void indexedBypassCommandsShouldBind(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues(
                        "agent.tools.bash.working-directory=" + tempDir,
                        "agent.tools.bash.allowed-paths[0]=" + tempDir,
                        "agent.tools.bash.allowed-commands[0]=git",
                        "agent.tools.bash.path-check-bypass-commands[0]=git",
                        "agent.tools.bash.path-check-bypass-commands[1]=curl"
                )
                .run(context -> {
                    BashToolProperties properties = context.getBean(BashToolProperties.class);
                    assertThat(properties.getPathCheckBypassCommands()).containsExactly("git", "curl");
                });
    }

    @Test
    void shellFeaturePropertiesShouldBindAndEnablePipeline(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello-shell\nworld");

        contextRunner
                .withPropertyValues(
                        "agent.tools.bash.working-directory=" + tempDir,
                        "agent.tools.bash.allowed-paths=" + tempDir,
                        "agent.tools.bash.allowed-commands=cat,rg",
                        "agent.tools.bash.path-checked-commands=cat",
                        "agent.tools.bash.shell-features-enabled=true",
                        "agent.tools.bash.shell-executable=bash",
                        "agent.tools.bash.shell-timeout-ms=15000",
                        "agent.tools.bash.max-command-chars=32000"
                )
                .run(context -> {
                    BashToolProperties properties = context.getBean(BashToolProperties.class);
                    assertThat(properties.isShellFeaturesEnabled()).isTrue();
                    assertThat(properties.getShellExecutable()).isEqualTo("bash");
                    assertThat(properties.getShellTimeoutMs()).isEqualTo(15000);
                    assertThat(properties.getMaxCommandChars()).isEqualTo(32000);

                    SystemBash bash = context.getBean(SystemBash.class);
                    JsonNode result = bash.invoke(Map.of("command", "cat demo.txt | rg hello-shell"));
                    assertThat(result.asText()).contains("exitCode: 0");
                    assertThat(result.asText()).contains("mode: shell");
                    assertThat(result.asText()).contains("hello-shell");
                });
    }

    @Test
    void shouldDefaultWorkingDirectoryToProjectRootFromConfigsDir(@TempDir Path tempDir) throws IOException {
        Path configsDir = tempDir.resolve("project").resolve("configs");
        Path projectDir = configsDir.getParent();
        Files.createDirectories(configsDir);
        Files.writeString(projectDir.resolve("demo.txt"), "hello-project-root");

        contextRunner
                .withPropertyValues(
                        "AGENT_CONFIG_DIR=" + configsDir,
                        "agent.tools.bash.allowed-paths=" + projectDir,
                        "agent.tools.bash.allowed-commands=cat"
                )
                .run(context -> {
                    SystemBash bash = context.getBean(SystemBash.class);
                    JsonNode result = bash.invoke(Map.of("command", "cat demo.txt"));
                    assertThat(bash.description()).contains("workingDirectory: " + projectDir.toAbsolutePath().normalize());
                    assertThat(result.asText()).contains("exitCode: 0");
                    assertThat(result.asText()).contains("hello-project-root");
                });
    }

    @Test
    void shouldUseExplicitWorkingDirectoryInsteadOfDerivedDefault(@TempDir Path tempDir) throws IOException {
        Path configsDir = tempDir.resolve("project").resolve("configs");
        Path explicitDir = tempDir.resolve("explicit");
        Files.createDirectories(configsDir);
        Files.createDirectories(explicitDir);
        Files.writeString(explicitDir.resolve("demo.txt"), "hello-explicit");

        contextRunner
                .withPropertyValues(
                        "AGENT_CONFIG_DIR=" + configsDir,
                        "agent.tools.bash.working-directory=" + explicitDir,
                        "agent.tools.bash.allowed-paths=" + explicitDir,
                        "agent.tools.bash.allowed-commands=cat"
                )
                .run(context -> {
                    SystemBash bash = context.getBean(SystemBash.class);
                    JsonNode result = bash.invoke(Map.of("command", "cat demo.txt"));
                    assertThat(bash.description()).contains("workingDirectory: " + explicitDir.toAbsolutePath().normalize());
                    assertThat(result.asText()).contains("hello-explicit");
                });
    }

    @Test
    void shouldUseConfiguredDirectoryWhenConfigDirIsNotNamedConfigs(@TempDir Path tempDir) throws IOException {
        Path configDir = tempDir.resolve("runtime-root");
        Files.createDirectories(configDir);

        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getSystemProperties().put(ConfigDirectorySupport.CONFIG_DIR_ENV, configDir.toString());

        assertThat(SystemBash.defaultWorkingDirectory(environment)).isEqualTo(configDir.toAbsolutePath().normalize());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BashToolProperties.class)
    static class BashToolConfiguration {

        @Bean
        SystemBash systemBash(BashToolProperties properties, ConfigurableEnvironment environment) {
            return new SystemBash(properties, environment);
        }
    }
}
