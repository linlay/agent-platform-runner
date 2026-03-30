package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefaultsPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldBindBudgetAndModeDefaults() {
        contextRunner
                .withPropertyValues(
                        "agent.defaults.budget.run-timeout-ms=600000",
                        "agent.defaults.budget.model.max-calls=40",
                        "agent.defaults.budget.model.timeout-ms=90000",
                        "agent.defaults.budget.model.retry-count=1",
                        "agent.defaults.budget.tool.max-calls=80",
                        "agent.defaults.budget.tool.timeout-ms=480000",
                        "agent.defaults.budget.tool.retry-count=2",
                        "agent.defaults.react.max-steps=72",
                        "agent.defaults.plan-execute.max-steps=48"
                )
                .run(context -> {
                    AgentDefaultsProperties properties = context.getBean(AgentDefaultsProperties.class);
                    assertThat(properties.defaultBudget().runTimeoutMs()).isEqualTo(600_000L);
                    assertThat(properties.defaultBudget().model().maxCalls()).isEqualTo(40);
                    assertThat(properties.defaultBudget().model().timeoutMs()).isEqualTo(90_000L);
                    assertThat(properties.defaultBudget().model().retryCount()).isEqualTo(1);
                    assertThat(properties.defaultBudget().tool().maxCalls()).isEqualTo(80);
                    assertThat(properties.defaultBudget().tool().timeoutMs()).isEqualTo(480_000L);
                    assertThat(properties.defaultBudget().tool().retryCount()).isEqualTo(2);
                    assertThat(properties.defaultReactMaxSteps()).isEqualTo(72);
                    assertThat(properties.defaultPlanExecuteMaxSteps()).isEqualTo(48);
                });
    }

    @EnableConfigurationProperties(AgentDefaultsProperties.class)
    static class TestConfiguration {
    }
}
