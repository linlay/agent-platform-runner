package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindAgentMemoryProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.memory.agent-memory.enabled=false",
                        "agent.memory.agent-memory.db-file-name=agent-memory.sqlite",
                        "agent.memory.agent-memory.context-top-n=7",
                        "agent.memory.agent-memory.embedding-provider-key=openai-like",
                        "agent.memory.agent-memory.embedding-model=text-embedding-3-large",
                        "agent.memory.agent-memory.embedding-dimension=3072"
                )
                .run(context -> {
                    AgentMemoryProperties properties = context.getBean(AgentMemoryProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getDbFileName()).isEqualTo("agent-memory.sqlite");
                    assertThat(properties.getContextTopN()).isEqualTo(7);
                    assertThat(properties.getEmbeddingProviderKey()).isEqualTo("openai-like");
                    assertThat(properties.getEmbeddingModel()).isEqualTo("text-embedding-3-large");
                    assertThat(properties.getEmbeddingDimension()).isEqualTo(3072);
                });
    }

    @Configuration
    @EnableConfigurationProperties(AgentMemoryProperties.class)
    static class TestConfig {
    }
}
