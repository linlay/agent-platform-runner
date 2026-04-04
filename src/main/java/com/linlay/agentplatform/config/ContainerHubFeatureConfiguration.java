package com.linlay.agentplatform.config;

import com.linlay.agentplatform.agent.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.sandbox.ContainerHubMountResolver;
import com.linlay.agentplatform.agent.runtime.sandbox.MountDirectoryConfig;
import com.linlay.agentplatform.agent.runtime.sandbox.ContainerHubSandboxService;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.config.properties.OwnerProperties;
import com.linlay.agentplatform.config.properties.PanProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.config.properties.ToolProperties;
import com.linlay.agentplatform.config.properties.ViewportProperties;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.agent.runtime.sandbox.ContainerHubClient;
import com.linlay.agentplatform.agent.runtime.sandbox.SystemContainerHubBash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

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
                "_sandbox_bash_ enabled as sandbox command tool, baseUrl={}, defaultEnvironmentId={}, defaultSandboxLevel={}",
                properties.getBaseUrl(),
                properties.getDefaultEnvironmentId(),
                properties.getDefaultSandboxLevel()
        );
        return new SystemContainerHubBash(properties, containerHubClient);
    }

    @Bean
    public ContainerHubMountResolver containerHubMountResolver(
            ChatStorageProperties chatWindowMemoryProperties,
            AgentMemoryProperties agentMemoryProperties,
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
            ProviderProperties providerProperties,
            OwnerProperties ownerProperties
    ) {
        RuntimeDirectoryHostPaths hostPaths = RuntimeDirectoryHostPaths.load(System.getenv());
        MountDirectoryConfig directories = new MountDirectoryConfig(
                chatWindowMemoryProperties == null ? null : chatWindowMemoryProperties.getDir(),
                agentMemoryProperties == null ? null : agentMemoryProperties.getStorage().getDir(),
                rootProperties == null ? null : rootProperties.getExternalDir(),
                panProperties == null ? null : panProperties.getExternalDir(),
                skillProperties == null ? null : skillProperties.getExternalDir(),
                agentProperties == null ? null : agentProperties.getExternalDir(),
                toolProperties == null ? null : toolProperties.getExternalDir(),
                resolveRegistriesDir(modelProperties, providerProperties, mcpProperties, viewportServerProperties),
                viewportProperties == null ? null : viewportProperties.getExternalDir(),
                teamProperties == null ? null : teamProperties.getExternalDir(),
                scheduleProperties == null ? null : scheduleProperties.getExternalDir(),
                ownerProperties == null ? null : ownerProperties.getExternalDir()
        );
        return new ContainerHubMountResolver(directories, hostPaths);
    }

    @Bean
    public ContainerHubSandboxService containerHubSandboxService(
            ContainerHubToolProperties properties,
            ContainerHubClient containerHubClient,
            ContainerHubMountResolver containerHubMountResolver
    ) {
        return new ContainerHubSandboxService(properties, containerHubClient, containerHubMountResolver);
    }

    private String resolveRegistriesDir(
            ModelProperties modelProperties,
            ProviderProperties providerProperties,
            McpProperties mcpProperties,
            ViewportServerProperties viewportServerProperties
    ) {
        String modelsDir = modelProperties == null ? null : modelProperties.getExternalDir();
        String providersDir = providerProperties == null ? null : providerProperties.getExternalDir();
        String mcpServersDir = mcpProperties == null || mcpProperties.getRegistry() == null
                ? null
                : mcpProperties.getRegistry().getExternalDir();
        String viewportServersDir = viewportServerProperties == null || viewportServerProperties.getRegistry() == null
                ? null
                : viewportServerProperties.getRegistry().getExternalDir();
        return commonRegistriesDir(modelsDir, providersDir, mcpServersDir, viewportServersDir);
    }

    private String commonRegistriesDir(String modelsDir, String providersDir, String mcpServersDir, String viewportServersDir) {
        return findCommonParent(modelsDir, "models", providersDir, "providers", mcpServersDir, "mcp-servers", viewportServersDir, "viewport-servers");
    }

    private String findCommonParent(String... values) {
        if (values == null || values.length == 0 || values.length % 2 != 0) {
            return null;
        }
        java.nio.file.Path parent = null;
        for (int i = 0; i < values.length; i += 2) {
            String pathValue = values[i];
            String childName = values[i + 1];
            if (!StringUtils.hasText(pathValue)) {
                return null;
            }
            java.nio.file.Path path = java.nio.file.Path.of(pathValue.trim()).normalize();
            java.nio.file.Path fileName = path.getFileName();
            java.nio.file.Path currentParent = path.getParent();
            if (fileName == null || currentParent == null || !childName.equals(fileName.toString())) {
                return null;
            }
            if (parent == null) {
                parent = currentParent;
                continue;
            }
            if (!parent.equals(currentParent)) {
                return null;
            }
        }
        return parent == null ? null : parent.toString();
    }

}
