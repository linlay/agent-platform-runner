package com.linlay.agentplatform.config;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
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
            assertThat(context.getBean(SkillProperties.class).getExternalDir()).isEqualTo("runtime/skills-market");
            assertThat(context.getBean(ScheduleProperties.class).getExternalDir()).isEqualTo("runtime/schedules");
            assertThat(context.getBean(ViewportProperties.class).getExternalDir()).isNull();
            assertThat(context.getBean(McpProperties.class).getRegistry().getExternalDir()).isEqualTo("runtime/mcp-servers");
            assertThat(context.getBean(ViewportServerProperties.class).getRegistry().getExternalDir()).isEqualTo("runtime/viewport-servers");
            assertThat(context.getBean(RootProperties.class).getExternalDir()).isEqualTo("runtime/root");
            assertThat(context.getBean(PanProperties.class).getExternalDir()).isEqualTo("runtime/pan");
            assertThat(context.getBean(ChatWindowMemoryProperties.class).getDir()).isEqualTo("runtime/chats");
        });
    }

    @Test
    void shouldAllowExplicitSkillAndScheduleOverrides() {
        contextRunner
                .withPropertyValues(
                        "agent.skills.external-dir=/tmp/skills-market",
                        "agent.schedule.external-dir=/tmp/schedules"
                )
                .run(context -> {
                    assertThat(context.getBean(SkillProperties.class).getExternalDir()).isEqualTo("/tmp/skills-market");
                    assertThat(context.getBean(ScheduleProperties.class).getExternalDir()).isEqualTo("/tmp/schedules");
                });
    }

    @Test
    void shouldBindDockerProfileDirectoriesToFixedOptLayout() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(RunnerDirectoryConfiguration.class)
                .profiles("docker")
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            assertThat(context.getBean(AgentProperties.class).getExternalDir()).isEqualTo("/opt/agents");
            assertThat(context.getBean(TeamProperties.class).getExternalDir()).isEqualTo("/opt/teams");
            assertThat(context.getBean(ModelProperties.class).getExternalDir()).isEqualTo("/opt/models");
            assertThat(context.getBean(ProviderProperties.class).getExternalDir()).isEqualTo("/opt/providers");
            assertThat(context.getBean(SkillProperties.class).getExternalDir()).isEqualTo("/opt/skills-market");
            assertThat(context.getBean(ScheduleProperties.class).getExternalDir()).isEqualTo("/opt/schedules");
            assertThat(context.getBean(McpProperties.class).getRegistry().getExternalDir()).isEqualTo("/opt/mcp-servers");
            assertThat(context.getBean(ViewportServerProperties.class).getRegistry().getExternalDir()).isEqualTo("/opt/viewport-servers");
            assertThat(context.getBean(RootProperties.class).getExternalDir()).isEqualTo("/opt/root");
            assertThat(context.getBean(PanProperties.class).getExternalDir()).isEqualTo("/opt/pan");
            assertThat(context.getBean(ChatWindowMemoryProperties.class).getDir()).isEqualTo("/opt/chats");
        }
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
