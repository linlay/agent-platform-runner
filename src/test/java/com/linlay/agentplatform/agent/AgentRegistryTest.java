package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.LocalToolInvoker;
import com.linlay.agentplatform.agent.runtime.McpToolInvoker;
import com.linlay.agentplatform.agent.runtime.ToolInvokerRouter;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.tool.PlatformCreateAgent;
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

class AgentRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseCachedAgentsUntilRefresh() throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        Files.writeString(agentsDir.resolve("demo_one.json"), """
                {
                  "description": "demo one",
                  "key": "demo_one",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": {
                    "backends": ["_bash_"],
                    "frontends": [],
                    "actions": []
                  },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "你是 demo one" }
                }
                """);

        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(agentsDir.toString());

        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
        LlmService llmService = new StubLlmService() {
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                TestSystemBashFactory.defaultBash(),
                new PlatformCreateAgent(agentsDir)
        ));
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
                null,
                null,
                toolInvokerRouter
        );

        assertThat(registry.listIds()).contains("demo_one");
        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);

        Files.writeString(agentsDir.resolve("demo_two.json"), """
                {
                  "description": "demo two",
                  "key": "demo_two",
                  "modelConfig": {
                    "modelKey": "bailian-qwen3-max"
                  },
                  "toolConfig": {
                    "backends": ["_bash_"],
                    "frontends": [],
                    "actions": []
                  },
                  "mode": "REACT",
                  "react": { "systemPrompt": "你是 demo two" }
                }
                """);

        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);

        registry.refreshAgents();
        assertThat(registry.get("demo_two").id()).isEqualTo("demo_two");

        Files.delete(agentsDir.resolve("demo_two.json"));
        assertThat(registry.listIds()).contains("demo_two");

        registry.refreshAgents();
        assertThatThrownBy(() -> registry.get("demo_two"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResolveAffectedAgentsByToolAndModelDependencies() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        Files.writeString(agentsDir.resolve("agent_alpha.json"), """
                {
                  "description": "alpha",
                  "key": "agent_alpha",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "toolConfig": { "backends": ["_bash_"], "frontends": [], "actions": [] },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "alpha" }
                }
                """);
        Files.writeString(agentsDir.resolve("agent_beta.json"), """
                {
                  "description": "beta",
                  "key": "agent_beta",
                  "modelConfig": { "modelKey": "siliconflow-deepseek-v3_2" },
                  "toolConfig": { "backends": ["datetime"], "frontends": [], "actions": [] },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "beta" }
                }
                """);

        AgentRegistry registry = createRegistry(agentsDir);

        assertThat(registry.findAgentIdsByTools(Set.of("_bash_"))).containsExactly("agent_alpha");
        assertThat(registry.findAgentIdsByTools(Set.of("datetime"))).containsExactly("agent_beta");
        assertThat(registry.findAgentIdsByModels(Set.of("bailian-qwen3-max"))).containsExactly("agent_alpha");
        assertThat(registry.findAgentIdsByModels(Set.of("siliconflow-deepseek-v3_2"))).containsExactly("agent_beta");
    }

    @Test
    void shouldLoadMixedJsonAndYamlAgentsAfterRefresh() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        Files.writeString(agentsDir.resolve("json_agent.json"), """
                {
                  "description": "json agent",
                  "key": "json_agent",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "json prompt" }
                }
                """);

        AgentRegistry registry = createRegistry(agentsDir);
        assertThat(registry.listIds()).containsExactly("json_agent");

        Files.writeString(agentsDir.resolve("yaml_agent.yml"), """
                key: yaml_agent
                description: yaml agent
                modelConfig:
                  modelKey: bailian-qwen3-max
                mode: ONESHOT
                plain:
                  systemPrompt: |
                    yaml prompt
                    second line
                """);

        registry.refreshAgents();

        assertThat(registry.listIds()).containsExactlyInAnyOrder("json_agent", "yaml_agent");
        assertThat(registry.get("yaml_agent").systemPrompt()).isEqualTo("yaml prompt\nsecond line");
    }

    @Test
    void shouldRefreshOnlySelectedAgents() throws Exception {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);

        Files.writeString(agentsDir.resolve("agent_alpha.json"), """
                {
                  "description": "alpha",
                  "key": "agent_alpha",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "toolConfig": { "backends": ["_bash_"], "frontends": [], "actions": [] },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "alpha-v1" }
                }
                """);
        Files.writeString(agentsDir.resolve("agent_beta.json"), """
                {
                  "description": "beta",
                  "key": "agent_beta",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "toolConfig": { "backends": ["_bash_"], "frontends": [], "actions": [] },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "beta-v1" }
                }
                """);

        AgentRegistry registry = createRegistry(agentsDir);

        Agent alphaBefore = registry.get("agent_alpha");
        Agent betaBefore = registry.get("agent_beta");

        Files.writeString(agentsDir.resolve("agent_alpha.json"), """
                {
                  "description": "alpha",
                  "key": "agent_alpha",
                  "modelConfig": { "modelKey": "bailian-qwen3-max" },
                  "toolConfig": { "backends": ["_bash_"], "frontends": [], "actions": [] },
                  "mode": "ONESHOT",
                  "plain": { "systemPrompt": "alpha-v2" }
                }
                """);

        registry.refreshAgentsByIds(Set.of("agent_alpha"), "test-selective");

        Agent alphaAfter = registry.get("agent_alpha");
        Agent betaAfter = registry.get("agent_beta");

        assertThat(alphaAfter).isNotSameAs(alphaBefore);
        assertThat(betaAfter).isSameAs(betaBefore);
    }

    @Test
    void shouldLoadTerminalAssistantExampleAgent() {
        Path agentsDir = Path.of("example/agents").toAbsolutePath().normalize();

        AgentRegistry registry = createRegistry(agentsDir);
        Agent terminalAssistant = registry.get("terminalAssistant");

        assertThat(registry.listIds()).contains("terminalAssistant");
        assertThat(terminalAssistant.mode()).isEqualTo(com.linlay.agentplatform.agent.runtime.AgentRuntimeMode.PLAN_EXECUTE);
        assertThat(terminalAssistant.tools()).contains("terminal_command_review");
        assertThat(terminalAssistant.description()).contains("终端辅助智能体");
    }

    private AgentRegistry createRegistry(Path agentsDir) {
        AgentProperties properties = new AgentProperties();
        properties.setExternalDir(agentsDir.toString());

        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties, TestModelRegistryServices.standardRegistry());
        LlmService llmService = new StubLlmService() {
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                TestSystemBashFactory.defaultBash(),
                new PlatformCreateAgent(agentsDir)
        ));
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
                null,
                null,
                toolInvokerRouter
        );
    }
}
