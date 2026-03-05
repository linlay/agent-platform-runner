package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.AgentCatalogProperties;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.CapabilityCatalogProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ViewportCatalogProperties;
import com.linlay.agentplatform.model.ModelCatalogProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.skill.SkillCatalogProperties;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamCatalogProperties;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.CapabilityRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectoryWatchServiceReloadRoutingTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRefreshOnlyAffectedAgentsWhenToolsChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        CapabilityRegistryService capabilityRegistryService = mock(CapabilityRegistryService.class);

        Set<String> changedTools = Set.of("tool.alpha");
        Set<String> affectedAgents = Set.of("agent.alpha");
        when(capabilityRegistryService.refreshCapabilities())
                .thenReturn(new CatalogDiff(changedTools, Set.of(), Set.of()));
        when(agentRegistry.findAgentIdsByTools(changedTools)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                capabilityRegistryService,
                mock(ModelRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpCapabilitySyncService.class)
        );
        try {
            callbackFor(service, tempDir.resolve("tools")).run();

            verify(capabilityRegistryService).refreshCapabilities();
            verify(agentRegistry).findAgentIdsByTools(changedTools);
            verify(agentRegistry).refreshAgentsByIds(affectedAgents, "tools-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentsWhenModelsChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        ModelRegistryService modelRegistryService = mock(ModelRegistryService.class);

        Set<String> changedModels = Set.of("model.alpha");
        Set<String> affectedAgents = Set.of("agent.beta");
        when(modelRegistryService.refreshModels())
                .thenReturn(new CatalogDiff(Set.of(), Set.of(), changedModels));
        when(agentRegistry.findAgentIdsByModels(changedModels)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(CapabilityRegistryService.class),
                modelRegistryService,
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpCapabilitySyncService.class)
        );
        try {
            callbackFor(service, tempDir.resolve("models")).run();

            verify(modelRegistryService).refreshModels();
            verify(agentRegistry).findAgentIdsByModels(changedModels);
            verify(agentRegistry).refreshAgentsByIds(affectedAgents, "models-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentsWhenMcpToolsChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        McpServerRegistryService mcpServerRegistryService = mock(McpServerRegistryService.class);
        McpCapabilitySyncService mcpCapabilitySyncService = mock(McpCapabilitySyncService.class);

        Set<String> changedTools = Set.of("mock.weather.query");
        Set<String> affectedAgents = Set.of("agent.gamma");
        when(mcpCapabilitySyncService.refreshCapabilities())
                .thenReturn(new CatalogDiff(Set.of(), changedTools, Set.of()));
        when(agentRegistry.findAgentIdsByTools(changedTools)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(CapabilityRegistryService.class),
                mock(ModelRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mcpServerRegistryService,
                mcpCapabilitySyncService
        );
        try {
            callbackFor(service, tempDir.resolve("mcp-servers")).run();

            verify(mcpServerRegistryService).refreshServers();
            verify(mcpCapabilitySyncService).refreshCapabilities();
            verify(agentRegistry).findAgentIdsByTools(changedTools);
            verify(agentRegistry).refreshAgentsByIds(affectedAgents, "mcp-registry-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldNotReloadAgentsWhenSkillsChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        when(skillRegistryService.refreshSkills()).thenReturn(new CatalogDiff(Set.of("skill.alpha"), Set.of(), Set.of()));

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(CapabilityRegistryService.class),
                mock(ModelRegistryService.class),
                skillRegistryService,
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpCapabilitySyncService.class)
        );
        try {
            callbackFor(service, tempDir.resolve("skills")).run();

            verify(skillRegistryService).refreshSkills();
            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    private DirectoryWatchService createService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            CapabilityRegistryService capabilityRegistryService,
            ModelRegistryService modelRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpCapabilitySyncService mcpCapabilitySyncService
    ) {
        AgentCatalogProperties agentCatalogProperties = new AgentCatalogProperties();
        agentCatalogProperties.setExternalDir(tempDir.resolve("agents").toString());

        ViewportCatalogProperties viewportCatalogProperties = new ViewportCatalogProperties();
        viewportCatalogProperties.setExternalDir(tempDir.resolve("viewports").toString());

        CapabilityCatalogProperties capabilityCatalogProperties = new CapabilityCatalogProperties();
        capabilityCatalogProperties.setExternalDir(tempDir.resolve("tools").toString());

        ModelCatalogProperties modelCatalogProperties = new ModelCatalogProperties();
        modelCatalogProperties.setExternalDir(tempDir.resolve("models").toString());

        SkillCatalogProperties skillCatalogProperties = new SkillCatalogProperties();
        skillCatalogProperties.setExternalDir(tempDir.resolve("skills").toString());

        TeamCatalogProperties teamCatalogProperties = new TeamCatalogProperties();
        teamCatalogProperties.setExternalDir(tempDir.resolve("teams").toString());

        McpProperties mcpProperties = new McpProperties();
        mcpProperties.getRegistry().setExternalDir(tempDir.resolve("mcp-servers").toString());

        return new DirectoryWatchService(
                agentRegistry,
                viewportRegistryService,
                capabilityRegistryService,
                modelRegistryService,
                skillRegistryService,
                teamRegistryService,
                mcpServerRegistryService,
                mcpCapabilitySyncService,
                agentCatalogProperties,
                viewportCatalogProperties,
                capabilityCatalogProperties,
                mcpProperties,
                modelCatalogProperties,
                skillCatalogProperties,
                teamCatalogProperties
        );
    }

    private Runnable callbackFor(DirectoryWatchService service, Path watchedPath) throws Exception {
        Field field = DirectoryWatchService.class.getDeclaredField("watchedDirs");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Path, Runnable> watched = (Map<Path, Runnable>) field.get(service);
        Path key = watchedPath.toAbsolutePath().normalize();
        Runnable callback = watched.get(key);
        assertThat(callback).isNotNull();
        return callback;
    }
}
