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
                "container_hub_bash enabled as native HTTP bridge, baseUrl={}, defaultEnvironmentId={}, defaultSandboxLevel={}",
                properties.getBaseUrl(),
                properties.getDefaultEnvironmentId(),
                properties.getDefaultSandboxLevel()
        );
        return new SystemContainerHubBash(properties, containerHubClient);
    }

    @Bean
    public ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            SkillProperties skillProperties,
            AgentProperties agentProperties,
            ToolProperties toolProperties,
            ModelProperties modelProperties,
            ViewportProperties viewportProperties,
            TeamProperties teamProperties,
            ScheduleProperties scheduleProperties,
            McpProperties mcpProperties,
            ProviderProperties providerProperties
    ) {
        return new ContainerHubMountResolver(
                properties,
                chatWindowMemoryProperties,
                skillProperties,
                agentProperties,
                toolProperties,
                modelProperties,
                viewportProperties,
                teamProperties,
                scheduleProperties,
                mcpProperties,
                providerProperties
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
