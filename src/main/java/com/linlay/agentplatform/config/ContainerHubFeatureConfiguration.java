package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.ContainerHubMountResolver;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import com.linlay.agentplatform.tool.SystemContainerHubBash;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "agent.tools.container-hub", name = "enabled", havingValue = "true")
public class ContainerHubFeatureConfiguration {

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
        return new SystemContainerHubBash(properties, containerHubClient);
    }

    @Bean
    public ContainerHubMountResolver containerHubMountResolver(
            ContainerHubToolProperties properties,
            DataProperties dataProperties,
            SkillProperties skillProperties
    ) {
        return new ContainerHubMountResolver(properties, dataProperties, skillProperties);
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
