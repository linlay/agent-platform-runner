package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.LocalToolInvoker;
import com.linlay.agentplatform.agent.runtime.McpToolInvoker;
import com.linlay.agentplatform.agent.runtime.ToolInvokerRouter;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.service.ActiveRunService;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.SystemBash;
import com.linlay.agentplatform.tool.TestSystemBashFactory;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.testsupport.StubLlmService;
import com.linlay.agentplatform.testsupport.TestModelRegistryServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseCachedAgentsUntilRefresh() throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        writeOneshotAgent(agentsDir, "demo_one", "Demo One", "demo one", "_bash_", "你是 demo one");

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(agentsDir.toString());

        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
        LlmService llmService = new StubLlmService() {
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(TestSystemBashFactory.defaultBash()));
        ChatWindowMemoryProperties memoryProperties = new ChatWindowMemoryProperties();
        memoryProperties.setDir(tempDir.resolve("chats").toString());
        ChatWindowMemoryStore memoryStore = new ChatWindowMemoryStore(new ObjectMapper(), memoryProperties);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ToolInvokerRouter toolInvokerRouter = new ToolInvokerRouter(
                toolRegistry,
                new LocalToolInvoker(toolRegistry),
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );

        AgentRegistry registry = new AgentRegistry(
                loader,
                llmService,
                toolRegistry,
                new ObjectMapper(),
                memoryStore,
                mock(FrontendSubmitCoordinator.class),
                mock(SkillRegistryService.class),
                new LoggingAgentProperties(),
                toolInvokerRouter,
                mock(ActiveRunService.class),
                beanFactory.getBeanProvider(com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService.class)
        );

        assertThat(registry.listIds()).contains("demo_one");
        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);

        writeReactAgent(agentsDir, "demo_two", "Demo Two", "demo two", "_bash_", "你是 demo two");

        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);

        registry.refreshAgents();
        assertThat(registry.get("demo_two").id()).isEqualTo("demo_two");

        deleteAgentDir(agentsDir, "demo_two");
        assertThat(registry.listIds()).contains("demo_two");

        registry.refreshAgents();
        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResolveAffectedAgentsByToolAndModelDependencies() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        writeOneshotAgent(agentsDir, "agent_alpha", "Agent Alpha", "alpha", "_bash_", "alpha");
        writeOneshotAgent(agentsDir, "agent_beta", "Agent Beta", "beta", "datetime", "beta", "siliconflow-deepseek-v3_2");

        AgentRegistry registry = createRegistry(agentsDir);

        assertThat(registry.findAgentIdsByTools(Set.of("_bash_"))).containsExactly("agent_alpha");
        assertThat(registry.findAgentIdsByTools(Set.of("datetime"))).containsExactly("agent_beta");
        assertThat(registry.findAgentIdsByModels(Set.of("bailian-qwen3-max"))).containsExactly("agent_alpha");
        assertThat(registry.findAgentIdsByModels(Set.of("siliconflow-deepseek-v3_2"))).containsExactly("agent_beta");
    }

    @Test
    void shouldLoadYamlAgentsAfterRefresh() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        writeOneshotAgent(agentsDir, "agent_one", "Agent One", "yaml agent one", null, "yaml prompt one");

        AgentRegistry registry = createRegistry(agentsDir);
        assertThat(registry.listIds()).containsExactly("agent_one");

        writeDirectoryAgent(
                agentsDir,
                "yaml_agent",
                """
                        key: yaml_agent
                        name: YAML Agent
                        role: YAML 示例
                        description: yaml agent
                        modelConfig:
                          modelKey: bailian-qwen3-max
                        mode: ONESHOT
                        """,
                "yaml prompt\nsecond line"
        );

        registry.refreshAgents();

        assertThat(registry.listIds()).containsExactlyInAnyOrder("agent_one", "yaml_agent");
        assertThat(registry.get("yaml_agent").systemPrompt()).isEqualTo("yaml prompt\nsecond line");
    }

    @Test
    void shouldFailFastOnLegacyJsonAgents() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("legacy.json"), "{\"key\":\"legacy\"}");

        assertThatThrownBy(() -> createRegistry(agentsDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy JSON agent files are no longer supported");
    }

    @Test
    void shouldRefreshOnlySelectedAgents() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        writeOneshotAgent(agentsDir, "agent_alpha", "Agent Alpha", "alpha", "_bash_", "alpha-v1");
        writeOneshotAgent(agentsDir, "agent_beta", "Agent Beta", "beta", "_bash_", "beta-v1");

        AgentRegistry registry = createRegistry(agentsDir);

        Agent alphaBefore = registry.get("agent_alpha");
        Agent betaBefore = registry.get("agent_beta");

        writeOneshotAgent(agentsDir, "agent_alpha", "Agent Alpha", "alpha", "_bash_", "alpha-v2");

        registry.refreshAgentsByIds(Set.of("agent_alpha"), "test-selective");

        Agent alphaAfter = registry.get("agent_alpha");
        Agent betaAfter = registry.get("agent_beta");

        assertThat(alphaAfter).isNotSameAs(alphaBefore);
        assertThat(betaAfter).isSameAs(betaBefore);
    }

    private AgentRegistry createRegistry(Path agentsDir) {
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(agentsDir.toString());

        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
        LlmService llmService = new StubLlmService() {
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(TestSystemBashFactory.defaultBash()));
        ChatWindowMemoryProperties memoryProperties = new ChatWindowMemoryProperties();
        memoryProperties.setDir(tempDir.resolve("chats").toString());
        ChatWindowMemoryStore memoryStore = new ChatWindowMemoryStore(new ObjectMapper(), memoryProperties);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ToolInvokerRouter toolInvokerRouter = new ToolInvokerRouter(
                toolRegistry,
                new LocalToolInvoker(toolRegistry),
                beanFactory.getBeanProvider(McpToolInvoker.class)
        );
        return new AgentRegistry(
                loader,
                llmService,
                toolRegistry,
                new ObjectMapper(),
                memoryStore,
                mock(FrontendSubmitCoordinator.class),
                mock(SkillRegistryService.class),
                new LoggingAgentProperties(),
                toolInvokerRouter,
                mock(ActiveRunService.class),
                beanFactory.getBeanProvider(com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService.class)
        );
    }

    private void writeOneshotAgent(Path agentsDir, String key, String name, String description, String backendTool, String prompt) throws IOException {
        writeOneshotAgent(agentsDir, key, name, description, backendTool, prompt, "bailian-qwen3-max");
    }

    private void writeOneshotAgent(
            Path agentsDir,
            String key,
            String name,
            String description,
            String backendTool,
            String prompt,
            String modelKey
    ) throws IOException {
        String toolConfig = backendTool == null
                ? "toolConfig: null"
                : """
                        toolConfig:
                          backends:
                            - %s
                          frontends: []
                          actions: []
                        """.formatted(backendTool).stripTrailing();
        writeDirectoryAgent(agentsDir, key, """
                key: %s
                name: %s
                role: Test Agent
                description: %s
                modelConfig:
                  modelKey: %s
                %s
                mode: ONESHOT
                """.formatted(key, name, description, modelKey, toolConfig), prompt);
    }

    private void writeReactAgent(Path agentsDir, String key, String name, String description, String backendTool, String prompt) throws IOException {
        String toolConfig = backendTool == null
                ? "toolConfig: null"
                : """
                        toolConfig:
                          backends:
                            - %s
                          frontends: []
                          actions: []
                        """.formatted(backendTool).stripTrailing();
        writeDirectoryAgent(agentsDir, key, """
                key: %s
                name: %s
                role: Test Agent
                description: %s
                modelConfig:
                  modelKey: bailian-qwen3-max
                %s
                mode: REACT
                react:
                  maxSteps: 6
                """.formatted(key, name, description, toolConfig), prompt);
    }

    private void writeDirectoryAgent(Path agentsDir, String key, String agentYaml, String agentsPrompt) throws IOException {
        Path agentDir = agentsDir.resolve(key);
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.yml"), agentYaml);
        Files.writeString(agentDir.resolve("AGENTS.md"), agentsPrompt);
    }

    private void deleteAgentDir(Path agentsDir, String key) throws IOException {
        Path agentDir = agentsDir.resolve(key);
        if (!Files.exists(agentDir)) {
            return;
        }
        try (var walk = Files.walk(agentDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }
}
