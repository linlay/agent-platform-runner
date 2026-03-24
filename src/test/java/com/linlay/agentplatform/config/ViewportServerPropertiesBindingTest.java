package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ViewportServerPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindNestedViewportServerProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.viewport-servers.enabled=true",
                        "agent.viewport-servers.protocol-version=2025-06",
                        "agent.viewport-servers.connect-timeout-ms=2200",
                        "agent.viewport-servers.retry=3",
                        "agent.viewport-servers.reconnect-interval-ms=47000",
                        "agent.viewport-servers.registry.external-dir=/tmp/viewport-servers"
                )
                .run(context -> {
                    ViewportServerProperties properties = context.getBean(ViewportServerProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getProtocolVersion()).isEqualTo("2025-06");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(2200);
                    assertThat(properties.getRetry()).isEqualTo(3);
                    assertThat(properties.getReconnectIntervalMs()).isEqualTo(47000);
                    assertThat(properties.getRegistry().getExternalDir()).isEqualTo("/tmp/viewport-servers");
                });
    }

    @Configuration
    @EnableConfigurationProperties(ViewportServerProperties.class)
    static class TestConfig {
    }
}
