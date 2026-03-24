package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.agent.runtime.ContainerHubMountResolver;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.config.properties.PanProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.config.properties.ToolProperties;
import com.linlay.agentplatform.config.properties.ViewportProperties;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.SystemContainerHubBash;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerHubFeatureConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerHubFeatureTestConfiguration.class);

    @Test
    void shouldStartWithoutContainerHubBeansWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ContainerHubClient.class);
            assertThat(context).doesNotHaveBean(SystemContainerHubBash.class);
            assertThat(context).doesNotHaveBean(ContainerHubSandboxService.class);
            assertThat(context).doesNotHaveBean(ContainerHubMountResolver.class);
        });
    }

    @Test
    void shouldCreateContainerHubBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "agent.tools.container-hub.enabled=true",
                        "agent.tools.container-hub.base-url=http://127.0.0.1:18080"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ContainerHubClient.class);
                    assertThat(context).hasSingleBean(SystemContainerHubBash.class);
                    assertThat(context).hasSingleBean(ContainerHubSandboxService.class);
                    assertThat(context).hasSingleBean(ContainerHubMountResolver.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ContainerHubToolProperties.class,
            DataProperties.class,
            ChatWindowMemoryProperties.class,
            RootProperties.class,
            PanProperties.class,
            SkillProperties.class,
            ToolProperties.class,
            AgentProperties.class,
            ModelProperties.class,
            ViewportProperties.class,
            ViewportServerProperties.class,
            TeamProperties.class,
            ScheduleProperties.class,
            McpProperties.class,
            ProviderProperties.class
    })
    @Import(ContainerHubFeatureConfiguration.class)
    static class ContainerHubFeatureTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
