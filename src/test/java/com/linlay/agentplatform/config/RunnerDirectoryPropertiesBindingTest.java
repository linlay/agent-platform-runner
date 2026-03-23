package com.linlay.agentplatform.config;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
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

    @Test
    void shouldDefaultDirectoriesToRuntimeLayout() {
        contextRunner.run(context -> {
            assertThat(context.getBean(AgentProperties.class).getExternalDir()).isEqualTo("runtime/agents");
            assertThat(context.getBean(TeamProperties.class).getExternalDir()).isEqualTo("runtime/teams");
            assertThat(context.getBean(ModelProperties.class).getExternalDir()).isEqualTo("runtime/models");
            assertThat(context.getBean(ProviderProperties.class).getExternalDir()).isEqualTo("runtime/providers");
            assertThat(context.getBean(ToolProperties.class).getExternalDir()).isNull();
            assertThat(context.getBean(SkillProperties.class).getExternalDir()).isNull();
            assertThat(context.getBean(ScheduleProperties.class).getExternalDir()).isNull();
            assertThat(context.getBean(ViewportProperties.class).getExternalDir()).isNull();
            assertThat(context.getBean(McpProperties.class).getRegistry().getExternalDir()).isEqualTo("runtime/mcp-servers");
            assertThat(context.getBean(ViewportServerProperties.class).getRegistry().getExternalDir()).isEqualTo("runtime/viewport-servers");
            assertThat(context.getBean(RootProperties.class).getExternalDir()).isEqualTo("runtime/root");
            assertThat(context.getBean(PanProperties.class).getExternalDir()).isEqualTo("runtime/pan");
            assertThat(context.getBean(ChatWindowMemoryProperties.class).getDir()).isEqualTo("runtime/chats");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            AgentProperties.class,
            TeamProperties.class,
            ModelProperties.class,
            ProviderProperties.class,
            ToolProperties.class,
            SkillProperties.class,
            ScheduleProperties.class,
            ViewportProperties.class,
            McpProperties.class,
            ViewportServerProperties.class,
            RootProperties.class,
            PanProperties.class,
            ChatWindowMemoryProperties.class
    })
    static class RunnerDirectoryConfiguration {
    }
}
