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

class ToolFileRegistryServiceContextTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateToolFileRegistryServiceBeanUsingClasspathBuiltins() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(ToolFileRegistryServiceContextConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolFileRegistryService.class);

                    ToolFileRegistryService service = context.getBean(ToolFileRegistryService.class);
                    assertThat(service.find("datetime")).isPresent();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ToolFileRegistryService.class)
    static class ToolFileRegistryServiceContextConfiguration {

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
