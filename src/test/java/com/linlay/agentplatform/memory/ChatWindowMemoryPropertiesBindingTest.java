package com.linlay.agentplatform.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWindowMemoryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindAutoRebuildSwitch() {
        contextRunner
                .withPropertyValues(
                        "memory.chats.index.sqlite-file=custom.db",
                        "memory.chats.index.auto-rebuild-on-incompatible-schema=false"
                )
                .run(context -> {
                    ChatWindowMemoryProperties properties = context.getBean(ChatWindowMemoryProperties.class);
                    assertThat(properties.getIndex().getSqliteFile()).isEqualTo("custom.db");
                    assertThat(properties.getIndex().isAutoRebuildOnIncompatibleSchema()).isFalse();
                });
    }

    @Configuration
    @EnableConfigurationProperties(ChatWindowMemoryProperties.class)
    static class TestConfig {
    }
}
