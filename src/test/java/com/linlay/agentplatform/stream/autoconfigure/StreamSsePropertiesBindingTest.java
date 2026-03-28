package com.linlay.agentplatform.stream.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSsePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(StreamSsePropertiesTestConfiguration.class);

    @Test
    void shouldUseSafeDefaults() {
        contextRunner.run(context -> {
            StreamSseProperties properties = context.getBean(StreamSseProperties.class);

            assertThat(properties.streamTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.includeToolPayloadEvents()).isFalse();
        });
    }

    @Test
    void shouldBindIncludeToolPayloadEvents() {
        contextRunner
                .withPropertyValues(
                        "agent.sse.include-tool-payload-events=true"
                )
                .run(context -> {
                    StreamSseProperties properties = context.getBean(StreamSseProperties.class);

                    assertThat(properties.includeToolPayloadEvents()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StreamSseProperties.class)
    static class StreamSsePropertiesTestConfiguration {
    }
}
