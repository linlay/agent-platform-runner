package com.linlay.agentplatform.config;

import com.linlay.agentplatform.model.ModelProtocol;
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
    void shouldBindProviderProtocolEndpointPath() {
        contextRunner
                .withPropertyValues(
                        "agent.providers.babelark.base-url=https://api.babelark.com",
                        "agent.providers.babelark.api-key=test-key",
                        "agent.providers.babelark.model=Qwen3.5-397B-A17B",
                        "agent.providers.babelark.protocols.OPENAI.endpoint-path=/v1/chat/completions"
                )
                .run(context -> {
                    AgentProviderProperties properties = context.getBean(AgentProviderProperties.class);
                    AgentProviderProperties.ProviderConfig provider = properties.getProvider("babelark");

                    assertThat(provider).isNotNull();
                    assertThat(provider.getBaseUrl()).isEqualTo("https://api.babelark.com");
                    assertThat(provider.getApiKey()).isEqualTo("test-key");
                    assertThat(provider.getModel()).isEqualTo("Qwen3.5-397B-A17B");
                    assertThat(provider.getProtocol(ModelProtocol.OPENAI)).isNotNull();
                    assertThat(provider.getProtocol(ModelProtocol.OPENAI).getEndpointPath()).isEqualTo("/v1/chat/completions");
                });
    }

    @Test
    void shouldIgnoreRemovedNewApiPathProperty() {
        contextRunner
                .withPropertyValues(
                        "agent.providers.babelark.base-url=https://api.babelark.com",
                        "agent.providers.babelark.api-key=test-key",
                        "agent.providers.babelark.new-api-path=/v1/chat/completions"
                )
                .run(context -> {
                    AgentProviderProperties properties = context.getBean(AgentProviderProperties.class);
                    AgentProviderProperties.ProviderConfig provider = properties.getProvider("babelark");

                    assertThat(provider).isNotNull();
                    assertThat(provider.getProtocols()).isEmpty();
                    assertThat(provider.getProtocol(ModelProtocol.OPENAI)).isNull();
                });
    }

    @Configuration
    @EnableConfigurationProperties(AgentProviderProperties.class)
    static class TestConfig {
    }
}
