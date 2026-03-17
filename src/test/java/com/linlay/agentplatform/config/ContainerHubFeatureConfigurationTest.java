package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.ContainerHubRunSandboxService;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.SystemContainerHubBash;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerHubFeatureConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerHubFeatureTestConfiguration.class);

    @Test
    void shouldStartWithoutContainerHubBeansWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ContainerHubClient.class);
            assertThat(context).doesNotHaveBean(SystemContainerHubBash.class);
            assertThat(context).doesNotHaveBean(ContainerHubRunSandboxService.class);
        });
    }

    @Test
    void shouldCreateContainerHubBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "agent.tools.container-hub.enabled=true",
                        "agent.tools.container-hub.base-url=http://127.0.0.1:18080"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ContainerHubClient.class);
                    assertThat(context).hasSingleBean(SystemContainerHubBash.class);
                    assertThat(context).hasSingleBean(ContainerHubRunSandboxService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContainerHubToolProperties.class)
    @Import(ContainerHubFeatureConfiguration.class)
    static class ContainerHubFeatureTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
