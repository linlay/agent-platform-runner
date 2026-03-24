package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class McpPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindNestedMcpProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.mcp-servers.enabled=true",
                        "agent.mcp-servers.protocol-version=2025-06",
                        "agent.mcp-servers.connect-timeout-ms=2100",
                        "agent.mcp-servers.retry=2",
                        "agent.mcp-servers.reconnect-interval-ms=45000",
                        "agent.mcp-servers.registry.external-dir=/tmp/mcp-servers"
                )
                .run(context -> {
                    McpProperties properties = context.getBean(McpProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getProtocolVersion()).isEqualTo("2025-06");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(2100);
                    assertThat(properties.getRetry()).isEqualTo(2);
                    assertThat(properties.getReconnectIntervalMs()).isEqualTo(45000);
                    assertThat(properties.getRegistry().getExternalDir()).isEqualTo("/tmp/mcp-servers");
                });
    }

    @Configuration
    @EnableConfigurationProperties(McpProperties.class)
    static class TestConfig {
    }
}
