package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.boot.AgentMemoryPropertyAliasEnvironmentPostProcessor;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withInitializer(context -> new AgentMemoryPropertyAliasEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldDefaultAgentMemoryDisabled() {
        contextRunner.run(context -> {
            AgentMemoryProperties properties = context.getBean(AgentMemoryProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getStorage().getDir()).isEqualTo("runtime/memory");
            assertThat(properties.getRemember().isEnabled()).isTrue();
        });
    }

    @Test
    void shouldBindAgentMemoryProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.memory.enabled=true",
                        "agent.memory.db-file-name=agent-memory.sqlite",
                        "agent.memory.context-top-n=7",
                        "agent.memory.embedding-provider-key=openai-like",
                        "agent.memory.embedding-model=text-embedding-3-large",
                        "agent.memory.embedding-dimension=3072",
                        "agent.memory.storage.dir=/tmp/memory-new",
                        "agent.memory.remember.enabled=false",
                        "agent.memory.remember.model-key=remember-v2",
                        "agent.memory.remember.timeout-ms=9000"
                )
                .run(context -> {
                    AgentMemoryProperties properties = context.getBean(AgentMemoryProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getDbFileName()).isEqualTo("agent-memory.sqlite");
                    assertThat(properties.getContextTopN()).isEqualTo(7);
                    assertThat(properties.getEmbeddingProviderKey()).isEqualTo("openai-like");
                    assertThat(properties.getEmbeddingModel()).isEqualTo("text-embedding-3-large");
                    assertThat(properties.getEmbeddingDimension()).isEqualTo(3072);
                    assertThat(properties.getStorage().getDir()).isEqualTo("/tmp/memory-new");
                    assertThat(properties.getRemember().isEnabled()).isFalse();
                    assertThat(properties.getRemember().getModelKey()).isEqualTo("remember-v2");
                    assertThat(properties.getRemember().getTimeoutMs()).isEqualTo(9000L);
                });
    }

    @Test
    void shouldBindDeprecatedPrefixesThroughAliasBridge() {
        contextRunner
                .withPropertyValues(
                        "agent.memory.agent-memory.enabled=true",
                        "agent.memory.agent-memory.db-file-name=agent-memory-legacy.sqlite",
                        "memory.storage.dir=/tmp/memory-legacy",
                        "memory.remember.enabled=false",
                        "memory.remember.model-key=remember-legacy",
                        "memory.remember.timeout-ms=12345"
                )
                .run(context -> {
                    AgentMemoryProperties properties = context.getBean(AgentMemoryProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getDbFileName()).isEqualTo("agent-memory-legacy.sqlite");
                    assertThat(properties.getStorage().getDir()).isEqualTo("/tmp/memory-legacy");
                    assertThat(properties.getRemember().isEnabled()).isFalse();
                    assertThat(properties.getRemember().getModelKey()).isEqualTo("remember-legacy");
                    assertThat(properties.getRemember().getTimeoutMs()).isEqualTo(12345L);
                });
    }

    @Test
    void shouldPreferNewKeysWhenOldAndNewPrefixesCoexist() {
        contextRunner
                .withPropertyValues(
                        "agent.memory.db-file-name=agent-memory-new.sqlite",
                        "agent.memory.storage.dir=/tmp/memory-new",
                        "agent.memory.remember.model-key=remember-new",
                        "agent.memory.agent-memory.db-file-name=agent-memory-legacy.sqlite",
                        "memory.storage.dir=/tmp/memory-legacy",
                        "memory.remember.model-key=remember-legacy"
                )
                .run(context -> {
                    AgentMemoryProperties properties = context.getBean(AgentMemoryProperties.class);
                    assertThat(properties.getDbFileName()).isEqualTo("agent-memory-new.sqlite");
                    assertThat(properties.getStorage().getDir()).isEqualTo("/tmp/memory-new");
                    assertThat(properties.getRemember().getModelKey()).isEqualTo("remember-new");
                });
    }

    @Configuration
    @EnableConfigurationProperties(AgentMemoryProperties.class)
    static class TestConfig {
    }
}
