package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindProviderExternalDirectory() {
        contextRunner
                .withPropertyValues(
                        "agent.providers.external-dir=/tmp/providers",
                        "agent.providers.refresh-interval-ms=45000"
                )
                .run(context -> {
                    ProviderProperties properties = context.getBean(ProviderProperties.class);
                    assertThat(properties.getExternalDir()).isEqualTo("/tmp/providers");
                    assertThat(properties.getRefreshIntervalMs()).isEqualTo(45000);
                });
    }

    @Configuration
    @EnableConfigurationProperties(ProviderProperties.class)
    static class TestConfig {
    }
}
