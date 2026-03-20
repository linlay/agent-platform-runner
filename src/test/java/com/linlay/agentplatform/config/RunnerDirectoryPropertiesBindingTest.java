package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerDirectoryPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RunnerDirectoryConfiguration.class);

    @Test
    void shouldBindRootAndPanProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.root.external-dir=/tmp/root",
                        "agent.pan.external-dir=/tmp/pan"
                )
                .run(context -> {
                    RootProperties rootProperties = context.getBean(RootProperties.class);
                    PanProperties panProperties = context.getBean(PanProperties.class);
                    assertThat(rootProperties.getExternalDir()).isEqualTo("/tmp/root");
                    assertThat(panProperties.getExternalDir()).isEqualTo("/tmp/pan");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RootProperties.class, PanProperties.class})
    static class RunnerDirectoryConfiguration {
    }
}
