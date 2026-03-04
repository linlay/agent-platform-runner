package com.linlay.agentplatform.config;

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
                        "agent.mcp.enabled=true",
                        "agent.mcp.protocol-version=2025-06",
                        "agent.mcp.connect-timeout-ms=2100",
                        "agent.mcp.retry=2",
                        "agent.mcp.registry.external-dir=/tmp/mcp-servers",
                        "agent.mcp.servers[0].server-key=mock",
                        "agent.mcp.servers[0].base-url=http://localhost:19080",
                        "agent.mcp.servers[0].endpoint-path=/mcp",
                        "agent.mcp.servers[0].read-timeout-ms=9100",
                        "agent.mcp.servers[0].tool-prefix=mock",
                        "agent.mcp.servers[0].headers.X-Api-Key=test-key",
                        "agent.mcp.servers[0].alias-map.legacy_weather=mock.weather.query"
                )
                .run(context -> {
                    McpProperties properties = context.getBean(McpProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getProtocolVersion()).isEqualTo("2025-06");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(2100);
                    assertThat(properties.getRetry()).isEqualTo(2);
                    assertThat(properties.getRegistry().getExternalDir()).isEqualTo("/tmp/mcp-servers");

                    assertThat(properties.getServers()).hasSize(1);
                    McpProperties.Server server = properties.getServers().getFirst();
                    assertThat(server.getServerKey()).isEqualTo("mock");
                    assertThat(server.getBaseUrl()).isEqualTo("http://localhost:19080");
                    assertThat(server.getEndpointPath()).isEqualTo("/mcp");
                    assertThat(server.getReadTimeoutMs()).isEqualTo(9100);
                    assertThat(server.getToolPrefix()).isEqualTo("mock");
                    assertThat(server.getHeaders()).containsEntry("X-Api-Key", "test-key");
                    assertThat(server.getAliasMap()).containsEntry("legacy_weather", "mock.weather.query");
                });
    }

    @Configuration
    @EnableConfigurationProperties(McpProperties.class)
    static class TestConfig {
    }
}
