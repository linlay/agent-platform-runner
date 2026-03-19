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

class ConfigDirectoryEnvironmentPostProcessorTest {

    private final ConfigDirectoryEnvironmentPostProcessor processor = new ConfigDirectoryEnvironmentPostProcessor();

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadStructuredConfigFilesFromConfiguredDirectory() throws Exception {
        Path configsDir = tempDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("auth.yml"), """
                agent:
                  auth:
                    enabled: false
                    local-public-key-file: auth/local-public-key.pem
                """);
        Files.writeString(configsDir.resolve("bash.yml"), """
                agent:
                  tools:
                    bash:
                      allowed-commands: ls,pwd
                """);
        Files.writeString(configsDir.resolve("container-hub.yml"), """
                agent:
                  tools:
                    container-hub:
                      enabled: true
                      default-environment-id: shell
                """);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, configsDir.toString()
        )));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("agent.auth.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.auth.local-public-key-file")).isEqualTo("auth/local-public-key.pem");
        assertThat(environment.getProperty("agent.tools.bash.allowed-commands")).isEqualTo("ls,pwd");
        assertThat(environment.getProperty("agent.tools.container-hub.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("agent.tools.container-hub.default-environment-id")).isEqualTo("shell");
    }

    @Test
    void shouldIgnoreMissingConfigDirectory() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, tempDir.resolve("missing").toString()
        )));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("agent.auth.enabled")).isNull();
    }

    @Test
    void shouldIgnoreNestedProvidersDirectory() throws Exception {
        Path configsDir = tempDir.resolve("configs");
        Path providersDir = configsDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("a.yml"), """
                key: ignored
                baseUrl: https://api.one.example
                """);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, configsDir.toString()
        )));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("key")).isNull();
        assertThat(environment.getProperty("baseUrl")).isNull();
    }
}
