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
    void shouldBindWorkspaceAndPanProperties() {
        contextRunner
                .withPropertyValues(
                        "agent.workspace.external-dir=/tmp/workspace",
                        "agent.pan.external-dir=/tmp/pan"
                )
                .run(context -> {
                    WorkspaceProperties workspaceProperties = context.getBean(WorkspaceProperties.class);
                    PanProperties panProperties = context.getBean(PanProperties.class);
                    assertThat(workspaceProperties.getExternalDir()).isEqualTo("/tmp/workspace");
                    assertThat(panProperties.getExternalDir()).isEqualTo("/tmp/pan");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({WorkspaceProperties.class, PanProperties.class})
    static class RunnerDirectoryConfiguration {
    }
}
