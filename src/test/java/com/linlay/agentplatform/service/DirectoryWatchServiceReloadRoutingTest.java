package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ProviderRegistryService;
import com.linlay.agentplatform.config.ViewportServerProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import com.linlay.agentplatform.schedule.ScheduleProperties;
import com.linlay.agentplatform.schedule.ScheduledQueryOrchestrator;
import com.linlay.agentplatform.skill.SkillProperties;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
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
        ToolFileRegistryService toolFileRegistryService = mock(ToolFileRegistryService.class);

        Set<String> changedTools = Set.of("tool.alpha");
        Set<String> affectedAgents = Set.of("agent.alpha");
        when(toolFileRegistryService.refreshTools())
                .thenReturn(new CatalogDiff(changedTools, Set.of(), Set.of()));
        when(agentRegistry.findAgentIdsByTools(changedTools)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                toolFileRegistryService,
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("tools"), tempDir.resolve("tools").resolve("nested/custom.yml"));

            verify(toolFileRegistryService).refreshTools();
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
                mock(ToolFileRegistryService.class),
                modelRegistryService,
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("models"), tempDir.resolve("models").resolve("folder/model.yml"));

            verify(modelRegistryService).refreshModels();
            verify(agentRegistry).findAgentIdsByModels(changedModels);
            verify(agentRegistry).refreshAgentsByIds(affectedAgents, "models-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshReferencingModelsAndAgentsWhenProvidersChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        ModelRegistryService modelRegistryService = mock(ModelRegistryService.class);
        ProviderRegistryService providerRegistryService = mock(ProviderRegistryService.class);

        Set<String> changedProviders = Set.of("provider.alpha");
        Set<String> referencingModels = Set.of("model.before");
        Set<String> changedModels = Set.of("model.after");
        Set<String> affectedModels = new LinkedHashSet<>(referencingModels);
        affectedModels.addAll(changedModels);
        Set<String> affectedAgents = Set.of("agent.provider");

        when(providerRegistryService.refreshProviders())
                .thenReturn(new CatalogDiff(Set.of(), Set.of(), changedProviders));
        when(modelRegistryService.findModelKeysByProviders(changedProviders)).thenReturn(referencingModels);
        when(modelRegistryService.refreshModels())
                .thenReturn(new CatalogDiff(Set.of(), Set.of(), changedModels));
        when(agentRegistry.findAgentIdsByModels(affectedModels)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                modelRegistryService,
                providerRegistryService,
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("providers"), tempDir.resolve("providers").resolve("folder/provider.yml"));

            verify(providerRegistryService).refreshProviders();
            verify(modelRegistryService).findModelKeysByProviders(changedProviders);
            verify(modelRegistryService).refreshModels();
            verify(agentRegistry).findAgentIdsByModels(affectedModels);
            verify(agentRegistry).refreshAgentsByIds(affectedAgents, "providers-directory");
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentsWhenMcpToolsChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        McpServerRegistryService mcpServerRegistryService = mock(McpServerRegistryService.class);
        McpToolSyncService mcpToolSyncService = mock(McpToolSyncService.class);

        Set<String> changedTools = Set.of("mock.weather.query");
        Set<String> affectedAgents = Set.of("agent.gamma");
        when(mcpToolSyncService.refreshTools())
                .thenReturn(new CatalogDiff(Set.of(), changedTools, Set.of()));
        when(agentRegistry.findAgentIdsByTools(changedTools)).thenReturn(affectedAgents);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mcpServerRegistryService,
                mcpToolSyncService,
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("mcp-servers"), tempDir.resolve("mcp-servers").resolve("nested/server.yml"));

            verify(mcpServerRegistryService).refreshServers();
            verify(mcpToolSyncService).refreshTools();
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
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                skillRegistryService,
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("skills"), tempDir.resolve("skills").resolve("demo/SKILL.md"));

            verify(skillRegistryService).refreshSkills();
            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshSchedulesWhenScheduleDirectoryChanged() throws Exception {
        ScheduledQueryOrchestrator orchestrator = mock(ScheduledQueryOrchestrator.class);
        DirectoryWatchService service = createService(
                mock(AgentRegistry.class),
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class),
                orchestrator
        );
        try {
            trigger(service, tempDir.resolve("schedules"), tempDir.resolve("schedules").resolve("folder/demo.yml"));
            verify(orchestrator).refreshAndReconcile();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshViewportRegistryWithoutReloadingAgentsWhenViewportServersChanged() throws Exception {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        ViewportServerRegistryService viewportServerRegistryService = mock(ViewportServerRegistryService.class);
        ViewportSyncService viewportSyncService = mock(ViewportSyncService.class);

        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                viewportServerRegistryService,
                viewportSyncService
        );
        try {
            trigger(service, tempDir.resolve("viewport-servers"), tempDir.resolve("viewport-servers").resolve("nested/server.yml"));

            verify(viewportServerRegistryService).refreshServers();
            verify(viewportSyncService).refreshViewports();
            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentWhenAgentPromptChanged() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/AGENTS.md"));

            verify(agentRegistry).refreshAgentsByIds(Set.of("demoAgent"), "agents-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentWhenAgentLocalToolChanged() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/tools/custom_tool.yml"));

            verify(agentRegistry).refreshAgentsByIds(Set.of("demoAgent"), "agents-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentWhenAgentLocalSkillChanged() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/skills/custom_skill/SKILL.md"));

            verify(agentRegistry).refreshAgentsByIds(Set.of("demoAgent"), "agents-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldIgnoreAgentMemoryChanges() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        DirectoryWatchService service = createService(
                agentRegistry,
                mock(ViewportRegistryService.class),
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/memory/2026-03/2026-03-19.md"));

            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshViewportRegistryWhenLocalViewportsChanged() {
        ViewportRegistryService viewportRegistryService = mock(ViewportRegistryService.class);
        DirectoryWatchService service = createService(
                mock(AgentRegistry.class),
                viewportRegistryService,
                mock(ToolFileRegistryService.class),
                mock(ModelRegistryService.class),
                mock(ProviderRegistryService.class),
                mock(SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(McpServerRegistryService.class),
                mock(McpToolSyncService.class),
                mock(ViewportServerRegistryService.class),
                mock(ViewportSyncService.class)
        );
        try {
            trigger(service, tempDir.resolve("viewports"), tempDir.resolve("viewports/cards/weather_card.html"));
            verify(viewportRegistryService).refreshViewports();
        } finally {
            service.destroy();
        }
    }

    private DirectoryWatchService createService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            ToolFileRegistryService toolFileRegistryService,
            ModelRegistryService modelRegistryService,
            ProviderRegistryService providerRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpToolSyncService mcpToolSyncService,
            ViewportServerRegistryService viewportServerRegistryService,
            ViewportSyncService viewportSyncService
    ) {
        return createService(
                agentRegistry,
                viewportRegistryService,
                toolFileRegistryService,
                modelRegistryService,
                providerRegistryService,
                skillRegistryService,
                teamRegistryService,
                mcpServerRegistryService,
                mcpToolSyncService,
                viewportServerRegistryService,
                viewportSyncService,
                mock(ScheduledQueryOrchestrator.class)
        );
    }

    private DirectoryWatchService createService(
            AgentRegistry agentRegistry,
            ViewportRegistryService viewportRegistryService,
            ToolFileRegistryService toolFileRegistryService,
            ModelRegistryService modelRegistryService,
            ProviderRegistryService providerRegistryService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            McpServerRegistryService mcpServerRegistryService,
            McpToolSyncService mcpToolSyncService,
            ViewportServerRegistryService viewportServerRegistryService,
            ViewportSyncService viewportSyncService,
            ScheduledQueryOrchestrator scheduledQueryOrchestrator
    ) {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir(tempDir.resolve("agents").toString());

        ViewportProperties viewportProperties = new ViewportProperties();
        viewportProperties.setExternalDir(tempDir.resolve("viewports").toString());

        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setExternalDir(tempDir.resolve("tools").toString());

        ModelProperties modelProperties = new ModelProperties();
        modelProperties.setExternalDir(tempDir.resolve("models").toString());

        ProviderProperties providerProperties = new ProviderProperties();
        providerProperties.setExternalDir(tempDir.resolve("providers").toString());

        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setExternalDir(tempDir.resolve("skills").toString());

        TeamProperties teamProperties = new TeamProperties();
        teamProperties.setExternalDir(tempDir.resolve("teams").toString());

        ScheduleProperties scheduleProperties = new ScheduleProperties();
        scheduleProperties.setExternalDir(tempDir.resolve("schedules").toString());

        McpProperties mcpProperties = new McpProperties();
        mcpProperties.getRegistry().setExternalDir(tempDir.resolve("mcp-servers").toString());

        ViewportServerProperties viewportServerProperties = new ViewportServerProperties();
        viewportServerProperties.getRegistry().setExternalDir(tempDir.resolve("viewport-servers").toString());

        return new DirectoryWatchService(
                agentRegistry,
                viewportRegistryService,
                toolFileRegistryService,
                modelRegistryService,
                providerRegistryService,
                skillRegistryService,
                teamRegistryService,
                mcpServerRegistryService,
                mcpToolSyncService,
                viewportServerRegistryService,
                viewportSyncService,
                scheduledQueryOrchestrator,
                agentProperties,
                viewportProperties,
                toolProperties,
                mcpProperties,
                viewportServerProperties,
                modelProperties,
                providerProperties,
                skillProperties,
                teamProperties,
                scheduleProperties
        );
    }

    private void trigger(DirectoryWatchService service, Path watchedRoot, Path changedPath) {
        assertThat(service.watchedRootPathsForTesting()).contains(watchedRoot.toAbsolutePath().normalize());
        service.triggerForTesting(watchedRoot, changedPath);
    }
}
