package com.linlay.agentplatform.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ToolProperties;

class ToolFileRegistryServiceContextTest {

    private static Path configuredToolsDir;

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateToolFileRegistryServiceBeanWithInjectedToolProperties() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        configuredToolsDir = toolsDir;

        new ApplicationContextRunner()
                .withUserConfiguration(ToolFileRegistryServiceContextConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolProperties.class);
                    assertThat(context).hasSingleBean(ToolFileRegistryService.class);

                    ToolFileRegistryService service = context.getBean(ToolFileRegistryService.class);
                    assertThat(service.list()).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ToolFileRegistryService.class)
    static class ToolFileRegistryServiceContextConfiguration {

        @Bean
        ToolProperties toolProperties() {
            ToolProperties properties = new ToolProperties();
            properties.setExternalDir(configuredToolsDir.toString());
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean("runtimeResourceSyncService")
        Object runtimeResourceSyncService() {
            return new Object();
        }
    }
}
