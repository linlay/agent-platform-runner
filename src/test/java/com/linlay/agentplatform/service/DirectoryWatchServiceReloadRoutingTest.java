package com.linlay.agentplatform.service;

import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.McpProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.service.mcp.McpServerRegistryService;
import com.linlay.agentplatform.service.mcp.McpToolSyncService;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportServerRegistryService;
import com.linlay.agentplatform.service.viewport.ViewportSyncService;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DirectoryWatchServiceReloadRoutingTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldNotExposeRemovedWatchRoots() {
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
                mock(ViewportSyncService.class)
        );
        try {
            assertThat(service.watchedRootPathsForTesting()).doesNotContain(
                    tempDir.resolve("skills").toAbsolutePath().normalize(),
                    tempDir.resolve("tools").toAbsolutePath().normalize(),
                    tempDir.resolve("viewports").toAbsolutePath().normalize()
            );
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
    void shouldRegisterOnlyAgentsRootAndFirstLevelAgentDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("agents/demoAgent"));
        Files.createDirectories(tempDir.resolve("agents/secondAgent"));
        Files.createDirectories(tempDir.resolve("agents/demoAgent/memory/2026-03"));
        Files.createDirectories(tempDir.resolve("agents/demoAgent/skills/custom_skill"));
        Files.createDirectories(tempDir.resolve("agents/demoAgent/tools"));

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
                mock(ViewportSyncService.class)
        );
        try {
            assertThat(service.registeredDirectoriesForTesting()).contains(
                    tempDir.resolve("agents").toAbsolutePath().normalize(),
                    tempDir.resolve("agents/demoAgent").toAbsolutePath().normalize(),
                    tempDir.resolve("agents/secondAgent").toAbsolutePath().normalize()
            ).doesNotContain(
                    tempDir.resolve("agents/demoAgent/memory").toAbsolutePath().normalize(),
                    tempDir.resolve("agents/demoAgent/memory/2026-03").toAbsolutePath().normalize(),
                    tempDir.resolve("agents/demoAgent/skills").toAbsolutePath().normalize(),
                    tempDir.resolve("agents/demoAgent/tools").toAbsolutePath().normalize()
            );
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
    void shouldLogWatchedRootsOnePerLineInDeterministicOrder(CapturedOutput output) throws Exception {
        createRuntimeRootDirectories();

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
                mock(ViewportSyncService.class)
        );
        try {
            String logs = output.getOut() + output.getErr();
            assertContainsInOrder(
                    logs,
                    "Directory watch root active: AGENTS=" + tempDir.resolve("agents").toAbsolutePath().normalize() + " (dirs=3)",
                    "Directory watch root active: MODELS=" + tempDir.resolve("models").toAbsolutePath().normalize() + " (dirs=1)",
                    "Directory watch root active: PROVIDERS=" + tempDir.resolve("providers").toAbsolutePath().normalize() + " (dirs=1)",
                    "Directory watch root active: MCP_SERVERS=" + tempDir.resolve("mcp-servers").toAbsolutePath().normalize() + " (dirs=1)",
                    "Directory watch root active: VIEWPORT_SERVERS=" + tempDir.resolve("viewport-servers").toAbsolutePath().normalize() + " (dirs=1)",
                    "Directory watch root active: TEAMS=" + tempDir.resolve("teams").toAbsolutePath().normalize() + " (dirs=1)",
                    "Directory watch root active: SCHEDULES=" + tempDir.resolve("schedules").toAbsolutePath().normalize() + " (dirs=1)"
            );
            assertThat(logs).doesNotContain("Directory watch roots active: [");
            assertThat(logs).doesNotContain("Directory watch root active: SKILLS_MARKET=");
            assertThat(logs).doesNotContain("Directory watch root active: TOOLS=");
            assertThat(logs).doesNotContain("Directory watch root active: VIEWPORTS=");
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldLogMissingRootsOnePerLineAndReportNoActiveRoots(CapturedOutput output) {
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
                mock(ViewportSyncService.class)
        );
        try {
            String logs = output.getOut() + output.getErr();
            assertThat(logs).contains("Directory watch roots active: none");
            assertThat(logs).contains("Directory watch root skipped: MODELS="
                    + tempDir.resolve("models").toAbsolutePath().normalize() + " (reason=missing)");
            assertThat(logs).contains("Directory watch root skipped: PROVIDERS="
                    + tempDir.resolve("providers").toAbsolutePath().normalize() + " (reason=missing)");
            assertThat(logs).contains("Directory watch root skipped: MCP_SERVERS="
                    + tempDir.resolve("mcp-servers").toAbsolutePath().normalize() + " (reason=missing)");
            assertThat(logs).contains("Directory watch root skipped: VIEWPORT_SERVERS="
                    + tempDir.resolve("viewport-servers").toAbsolutePath().normalize() + " (reason=missing)");
            assertThat(logs).doesNotContain("Directory watch roots skipped: [");
            assertThat(logs).doesNotContain("Directory watch root skipped: SKILLS_MARKET=");
            assertThat(logs).doesNotContain("Directory watch root skipped: TOOLS=");
            assertThat(logs).doesNotContain("Directory watch root skipped: VIEWPORTS=");
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRegisterWatcherWhenNewAgentDirectoryIsCreated() throws Exception {
        Files.createDirectories(tempDir.resolve("agents/demoAgent"));

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
                mock(ViewportSyncService.class)
        );
        try {
            Path newAgentDir = tempDir.resolve("agents/newAgent");
            Files.createDirectories(newAgentDir);

            assertThat(waitForRegisteredDirectory(service, newAgentDir, 5_000)).isTrue();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldNotRegisterWatcherForNestedAgentDataDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("agents/demoAgent"));

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
                mock(ViewportSyncService.class)
        );
        try {
            Path memoryDir = tempDir.resolve("agents/demoAgent/memory");
            Path monthDir = memoryDir.resolve("2026-03");
            Files.createDirectories(monthDir);

            assertThat(remainsUnregistered(service, memoryDir, 1_000)).isTrue();
            assertThat(remainsUnregistered(service, monthDir, 1_000)).isTrue();
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
    void shouldRefreshOnlyAffectedAgentWhenAgentDefinitionChanged() {
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
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/agent.yml"));

            verify(agentRegistry).refreshAgentsByIds(Set.of("demoAgent"), "agents-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentWhenAgentSoulChanged() {
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
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/SOUL.md"));

            verify(agentRegistry).refreshAgentsByIds(Set.of("demoAgent"), "agents-directory");
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldRefreshOnlyAffectedAgentWhenPlanPromptChanged() {
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
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/AGENTS.plan.md"));

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
    void shouldIgnoreAgentExperienceChanges() {
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
            trigger(service, tempDir.resolve("agents"), tempDir.resolve("agents/demoAgent/experiences/web-scraping/site-a-login.md"));

            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldIgnoreAgentLocalToolChanges() {
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

            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldIgnoreAgentLocalSkillChanges() {
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

            verify(agentRegistry, never()).refreshAgentsByIds(anySet(), anyString());
            verify(agentRegistry, never()).refreshAgents();
        } finally {
            service.destroy();
        }
    }

    @Test
    void shouldExposeWatchedRootPathsForConfiguredRuntimeRoots() {
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
                mock(ViewportSyncService.class)
        );
        try {
            assertThat(service.watchedRootPathsForTesting()).contains(
                    tempDir.resolve("agents").toAbsolutePath().normalize(),
                    tempDir.resolve("teams").toAbsolutePath().normalize(),
                    tempDir.resolve("schedules").toAbsolutePath().normalize(),
                    tempDir.resolve("models").toAbsolutePath().normalize(),
                    tempDir.resolve("providers").toAbsolutePath().normalize(),
                    tempDir.resolve("mcp-servers").toAbsolutePath().normalize(),
                    tempDir.resolve("viewport-servers").toAbsolutePath().normalize()
            ).doesNotContain(
                    tempDir.resolve("skills").toAbsolutePath().normalize(),
                    tempDir.resolve("tools").toAbsolutePath().normalize(),
                    tempDir.resolve("viewports").toAbsolutePath().normalize()
            );
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

    private void createRuntimeRootDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("agents"));
        Files.createDirectories(tempDir.resolve("agents/demoAgent"));
        Files.createDirectories(tempDir.resolve("agents/secondAgent"));
        Files.createDirectories(tempDir.resolve("models"));
        Files.createDirectories(tempDir.resolve("providers"));
        Files.createDirectories(tempDir.resolve("mcp-servers"));
        Files.createDirectories(tempDir.resolve("viewport-servers"));
        Files.createDirectories(tempDir.resolve("teams"));
        Files.createDirectories(tempDir.resolve("schedules"));
        Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(tempDir.resolve("tools"));
        Files.createDirectories(tempDir.resolve("viewports"));
    }

    private boolean waitForRegisteredDirectory(DirectoryWatchService service, Path path, long timeoutMs) throws InterruptedException {
        Path normalized = path.toAbsolutePath().normalize();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (service.registeredDirectoriesForTesting().contains(normalized)) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return service.registeredDirectoriesForTesting().contains(normalized);
    }

    private boolean remainsUnregistered(DirectoryWatchService service, Path path, long timeoutMs) throws InterruptedException {
        Path normalized = path.toAbsolutePath().normalize();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (service.registeredDirectoriesForTesting().contains(normalized)) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return !service.registeredDirectoriesForTesting().contains(normalized);
    }

    private void assertContainsInOrder(String text, String... fragments) {
        int offset = -1;
        for (String fragment : fragments) {
            int next = text.indexOf(fragment, offset + 1);
            assertThat(next)
                    .withFailMessage("Expected fragment after index %s: %s%nCaptured logs:%n%s", offset, fragment, text)
                    .isGreaterThan(offset);
            offset = next;
        }
    }
}
