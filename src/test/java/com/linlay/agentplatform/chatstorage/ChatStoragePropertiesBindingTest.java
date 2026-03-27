package com.linlay.agentplatform.chatstorage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStoragePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindAutoRebuildSwitch() {
        contextRunner
                .withPropertyValues(
                        "chat.storage.index.sqlite-file=custom.db",
                        "chat.storage.index.auto-rebuild-on-incompatible-schema=false"
                )
                .run(context -> {
                    ChatStorageProperties properties = context.getBean(ChatStorageProperties.class);
                    assertThat(properties.getIndex().getSqliteFile()).isEqualTo("custom.db");
                    assertThat(properties.getIndex().isAutoRebuildOnIncompatibleSchema()).isFalse();
                });
    }

    @Configuration
    @EnableConfigurationProperties(ChatStorageProperties.class)
    static class TestConfig {
    }
}
