package com.linlay.agentplatform.config;

import com.linlay.agentplatform.agent.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.ContainerHubMountResolver;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.SystemContainerHubBash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.tools.container-hub", name = "enabled", havingValue = "true")
public class ContainerHubFeatureConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ContainerHubFeatureConfiguration.class);

    @Bean
    public ContainerHubClient containerHubClient(
            ContainerHubToolProperties properties,
            ObjectMapper objectMapper
    ) {
        return new ContainerHubClient(properties, objectMapper);
    }

    @Bean
    public SystemContainerHubBash systemContainerHubBash(
            ContainerHubToolProperties properties,
            ContainerHubClient containerHubClient
    ) {
        log.info(
                "sandbox_bash enabled as sandbox command tool, baseUrl={}, defaultEnvironmentId={}, defaultSandboxLevel={}",
                properties.getBaseUrl(),
                properties.getDefaultEnvironmentId(),
                properties.getDefaultSandboxLevel()
        );
        return new SystemContainerHubBash(properties, containerHubClient);
    }

    @Bean
    public ContainerHubMountResolver containerHubMountResolver(
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            RootProperties rootProperties,
            PanProperties panProperties,
            SkillProperties skillProperties,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
            ViewportServerProperties viewportServerProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load();
        return new ContainerHubMountResolver(
                chatWindowMemoryProperties,
                rootProperties,
                panProperties,
                skillProperties,
                agentProperties,
                toolProperties,
                modelProperties,
                viewportProperties,
                viewportServerProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties,
                hostPaths
        );
    }

    @Bean
    public ContainerHubSandboxService containerHubSandboxService(
            ContainerHubToolProperties properties,
            ContainerHubClient containerHubClient,
            ContainerHubMountResolver containerHubMountResolver
    ) {
        return new ContainerHubSandboxService(properties, containerHubClient, containerHubMountResolver);
    }
}
