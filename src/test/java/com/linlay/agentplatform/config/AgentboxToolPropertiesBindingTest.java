package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentboxToolPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentboxToolConfiguration.class);

    @Test
    void shouldBindAgentboxToolProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.tools.agentbox.enabled=true",
                        "agent.tools.agentbox.base-url=http://127.0.0.1:18080",
                        "agent.tools.agentbox.auth-token=secret-token",
                        "agent.tools.agentbox.default-runtime=python",
                        "agent.tools.agentbox.default-version=3.11",
                        "agent.tools.agentbox.default-cwd=/workspace/project",
                        "agent.tools.agentbox.request-timeout-ms=45000"
                )
                .run(context -> {
                    AgentboxToolProperties properties = context.getBean(AgentboxToolProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getBaseUrl()).isEqualTo("http://127.0.0.1:18080");
                    assertThat(properties.getAuthToken()).isEqualTo("secret-token");
                    assertThat(properties.getDefaultRuntime()).isEqualTo("python");
                    assertThat(properties.getDefaultVersion()).isEqualTo("3.11");
                    assertThat(properties.getDefaultCwd()).isEqualTo("/workspace/project");
                    assertThat(properties.getRequestTimeoutMs()).isEqualTo(45000);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentboxToolProperties.class)
    static class AgentboxToolConfiguration {
    }
}
