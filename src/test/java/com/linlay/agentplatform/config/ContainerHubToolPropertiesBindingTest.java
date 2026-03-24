package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerHubToolPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerHubToolConfiguration.class);

    @Test
    void shouldBindContainerHubToolProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.tools.container-hub.enabled=true",
                        "agent.tools.container-hub.base-url=http://127.0.0.1:18080",
                        "agent.tools.container-hub.auth-token=secret-token",
                        "agent.tools.container-hub.default-environment-id=shell",
                        "agent.tools.container-hub.request-timeout-ms=45000"
                )
                .run(context -> {
                    ContainerHubToolProperties properties = context.getBean(ContainerHubToolProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getBaseUrl()).isEqualTo("http://127.0.0.1:18080");
                    assertThat(properties.getAuthToken()).isEqualTo("secret-token");
                    assertThat(properties.getDefaultEnvironmentId()).isEqualTo("shell");
                    assertThat(properties.getRequestTimeoutMs()).isEqualTo(45000);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContainerHubToolProperties.class)
    static class ContainerHubToolConfiguration {
    }
}
