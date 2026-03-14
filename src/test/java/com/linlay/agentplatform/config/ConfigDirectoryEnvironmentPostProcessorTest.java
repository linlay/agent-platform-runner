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
    void shouldLoadStructuredConfigFilesFromConfiguredDirectory() throws Exception {
        Path configsDir = tempDir.resolve("configs");
        Path providersDir = configsDir.resolve("providers");
        Files.createDirectories(providersDir);
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
        Files.writeString(providersDir.resolve("babelark.yml"), """
                agent:
                  providers:
                    babelark:
                      base-url: https://api.babelark.com
                      api-key: test-key
                      model: Qwen3.5-397B-A17B
                      protocols:
                        OPENAI:
                          endpoint-path: /v1/chat/completions
                """);
        Files.writeString(providersDir.resolve("ignored.example.yml"), """
                agent:
                  providers:
                    ignored:
                      base-url: https://ignored.example.com
                """);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, configsDir.toString()
        )));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("agent.auth.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.auth.local-public-key-file")).isEqualTo("auth/local-public-key.pem");
        assertThat(environment.getProperty("agent.tools.bash.allowed-commands")).isEqualTo("ls,pwd");
        assertThat(environment.getProperty("agent.providers.babelark.base-url")).isEqualTo("https://api.babelark.com");
        assertThat(environment.getProperty("agent.providers.babelark.protocols.OPENAI.endpoint-path")).isEqualTo("/v1/chat/completions");
        assertThat(environment.getProperty("agent.providers.ignored.base-url")).isNull();
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
    void shouldRejectDuplicateProviderKeysAcrossFiles() throws Exception {
        Path configsDir = tempDir.resolve("configs");
        Path providersDir = configsDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("a.yml"), """
                agent:
                  providers:
                    babelark:
                      base-url: https://api.one.example
                """);
        Files.writeString(providersDir.resolve("b.yml"), """
                agent:
                  providers:
                    babelark:
                      base-url: https://api.two.example
                """);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                ConfigDirectorySupport.CONFIG_DIR_ENV, configsDir.toString()
        )));

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate provider key 'babelark'");
    }
}
