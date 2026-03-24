package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.LlmInteractionLogProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAgentPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingPropertiesTestConfiguration.class);

    @Test
    void shouldUseSafeDefaults() {
        contextRunner.run(context -> {
            LoggingAgentProperties properties = context.getBean(LoggingAgentProperties.class);
            LlmInteractionLogProperties llm = context.getBean(LlmInteractionLogProperties.class);

            assertThat(properties.getRequest().isEnabled()).isTrue();
            assertThat(properties.getRequest().isIncludeQuery()).isTrue();
            assertThat(properties.getRequest().isIncludeBody()).isFalse();
            assertThat(properties.getSse().isEnabled()).isFalse();
            assertThat(properties.getSse().isIncludePayload()).isFalse();
            assertThat(properties.getSse().getEventWhitelist()).isEmpty();

            assertThat(llm.isEnabled()).isTrue();
            assertThat(llm.isMaskSensitive()).isTrue();
        });
    }

    @Test
    void shouldBindLoggingAgentPropertiesAndLlmInteractionMigrationKey() {
        contextRunner
                .withPropertyValues(
                        "logging.agent.request.enabled=false",
                        "logging.agent.request.include-query=false",
                        "logging.agent.request.include-body=true",
                        "logging.agent.tool.include-args=true",
                        "logging.agent.tool.include-result=true",
                        "logging.agent.action.enabled=false",
                        "logging.agent.sse.enabled=true",
                        "logging.agent.sse.include-payload=true",
                        "logging.agent.sse.event-whitelist=run.start,tool.start,tool.result",
                        "logging.agent.llm.interaction.enabled=false",
                        "logging.agent.llm.interaction.mask-sensitive=false"
                )
                .run(context -> {
                    LoggingAgentProperties properties = context.getBean(LoggingAgentProperties.class);
                    LlmInteractionLogProperties llm = context.getBean(LlmInteractionLogProperties.class);

                    assertThat(properties.getRequest().isEnabled()).isFalse();
                    assertThat(properties.getRequest().isIncludeQuery()).isFalse();
                    assertThat(properties.getRequest().isIncludeBody()).isTrue();
                    assertThat(properties.getTool().isIncludeArgs()).isTrue();
                    assertThat(properties.getTool().isIncludeResult()).isTrue();
                    assertThat(properties.getAction().isEnabled()).isFalse();
                    assertThat(properties.getSse().isEnabled()).isTrue();
                    assertThat(properties.getSse().isIncludePayload()).isTrue();
                    assertThat(properties.getSse().getEventWhitelist()).containsExactly("run.start", "tool.start", "tool.result");

                    assertThat(llm.isEnabled()).isFalse();
                    assertThat(llm.isMaskSensitive()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            LoggingAgentProperties.class,
            LlmInteractionLogProperties.class
    })
    static class LoggingPropertiesTestConfiguration {
    }
}
