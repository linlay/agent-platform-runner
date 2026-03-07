package com.linlay.agentplatform.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.service.McpToolSyncService;
import com.linlay.agentplatform.config.AgentFileCreateToolProperties;
import com.linlay.agentplatform.config.AgentProviderProperties;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.model.ModelProperties;
import com.linlay.agentplatform.model.ModelRegistryService;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CityDateTime cityDateTimeTool = new CityDateTime();
    private final SystemBash bashTool = TestSystemBashFactory.defaultBash();

    @Test
    void cityDateTimeShouldReturnRealtimeTimeWithTimezone() {
        Map<String, Object> args = Map.of("city", "Shanghai");

        JsonNode result = cityDateTimeTool.invoke(args);

        assertThat(result.path("timezone").asText()).isEqualTo("UTC+8");
        assertThat(result.path("source").asText()).isEqualTo("system-clock");
        assertThat(result.path("date").asText()).isNotBlank();
        assertThat(result.path("weekday").asText()).startsWith("星期");
        assertThat(result.path("lunarDate").asText()).isNotBlank();
        assertThat(result.path("time").asText()).isNotBlank();
        assertThatCode(() -> ZonedDateTime.parse(result.path("iso").asText())).doesNotThrowAnyException();
    }

    @Test
    void backendToolMetadataShouldOverrideNativeToolDefinition(@TempDir Path tempDir) throws IOException {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("city_datetime.backend"), """
                {
                  "tools": [
                    {
                      "type": "function",
                      "name": "city_datetime",
                      "description": "city datetime from backend",
                      "afterCallHint": "use city datetime prompt",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "city": {"type": "string"}
                        },
                        "required": ["city"],
                        "additionalProperties": false
                      }
                    }
                  ]
                }
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());
        ToolFileRegistryService toolFileRegistryService = new ToolFileRegistryService(
                new ObjectMapper(),
                properties
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolFileRegistryService", toolFileRegistryService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(new CityDateTime()),
                beanFactory.getBeanProvider(ToolFileRegistryService.class)
        );

        BaseTool cityTool = toolRegistry.list().stream()
                .filter(tool -> "city_datetime".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        assertThat(cityTool.description()).isEqualTo("city datetime from backend");
        assertThat(cityTool.afterCallHint()).isEqualTo("use city datetime prompt");
        assertThat(cityTool.parametersSchema().get("required")).isEqualTo(List.of("city"));
        assertThat(toolRegistry.invoke("city_datetime", Map.of("city", "Shanghai")).path("timezone").asText())
                .isEqualTo("UTC+8");
    }

    @Test
    void mcpToolShouldAppearAsVirtualTool() {
        ToolDescriptor mcpDescriptor = new ToolDescriptor(
                "mock.weather.query",
                "mock weather",
                "use weather viewport card",
                Map.of("type", "object"),
                false,
                true,
                ToolKind.BACKEND,
                "function",
                "mcp://mock/mock.weather.query",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        McpToolSyncService mcpToolSyncService = mock(McpToolSyncService.class);
        when(mcpToolSyncService.list()).thenReturn(List.of(mcpDescriptor));
        when(mcpToolSyncService.find("mock.weather.query")).thenReturn(java.util.Optional.of(mcpDescriptor));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("mcpToolSyncService", mcpToolSyncService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(new CityDateTime()),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class)
        );

        assertThat(toolRegistry.list().stream().map(BaseTool::name))
                .contains("mock.weather.query");
        BaseTool mcpTool = toolRegistry.list().stream()
                .filter(tool -> "mock.weather.query".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertThat(mcpTool.afterCallHint()).isEqualTo("use weather viewport card");
        assertThat(toolRegistry.descriptor("mock.weather.query"))
                .isPresent()
                .get()
                .extracting(ToolDescriptor::sourceType)
                .isEqualTo("mcp");
    }

    @Test
    void localToolShouldWinWhenMcpToolNameConflicts() {
        ToolDescriptor conflictDescriptor = new ToolDescriptor(
                "city_datetime",
                "remote city time",
                "",
                Map.of("type", "object"),
                false,
                true,
                ToolKind.BACKEND,
                "function",
                "mcp://mock/city_datetime",
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        McpToolSyncService mcpToolSyncService = mock(McpToolSyncService.class);
        when(mcpToolSyncService.list()).thenReturn(List.of(conflictDescriptor));
        when(mcpToolSyncService.find("city_datetime")).thenReturn(java.util.Optional.of(conflictDescriptor));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("mcpToolSyncService", mcpToolSyncService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(new CityDateTime()),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class)
        );

        assertThat(toolRegistry.descriptor("city_datetime"))
                .isPresent()
                .get()
                .extracting(ToolDescriptor::sourceType)
                .isEqualTo("local");
        assertThat(toolRegistry.invoke("city_datetime", Map.of("city", "Shanghai")).path("timezone").asText())
                .isEqualTo("UTC+8");
    }

    @Test
    void bashToolShouldRejectWhenAllowedCommandsAreNotConfigured() {
        JsonNode result = bashTool.invoke(Map.of("command", "cat /etc/passwd"));
        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("agent.tools.bash.allowed-commands");
    }

    @Test
    void bashToolShouldRejectDefaultLsWhenWhitelistIsEmpty() {
        JsonNode result = bashTool.invoke(Map.of("command", "ls"));
        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("agent.tools.bash.allowed-commands");
    }

    @Test
    void bashToolShouldRejectRelativePathWhenWorkingDirectoryNotInAllowedPaths(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello");
        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(), Set.of("cat"), Set.of());

        JsonNode result = localBashTool.invoke(Map.of("command", "cat demo.txt"));

        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories");
    }

    @Test
    void bashToolShouldAllowRelativeAndAbsolutePathsInsideAllowedPaths(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello");
        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(tempDir), Set.of("cat"), Set.of());

        JsonNode relativeResult = localBashTool.invoke(Map.of("command", "cat demo.txt"));
        JsonNode absoluteResult = localBashTool.invoke(Map.of("command", "cat " + file));

        assertThat(relativeResult.asText()).contains("exitCode: 0");
        assertThat(relativeResult.asText()).contains("hello");
        assertThat(absoluteResult.asText()).contains("exitCode: 0");
        assertThat(absoluteResult.asText()).contains("hello");
    }

    @Test
    void bashToolShouldAllowConfiguredAbsolutePath(@TempDir Path tempDir) throws IOException {
        Path externalDir = tempDir.resolve("opt");
        Files.createDirectories(externalDir);
        Path keyFile = externalDir.resolve("demo.key");
        Files.writeString(keyFile, "secret");

        SystemBash localBashTool = TestSystemBashFactory.bash(
                Path.of(System.getProperty("user.dir", ".")),
                List.of(externalDir),
                Set.of("cat"),
                Set.of()
        );
        JsonNode result = localBashTool.invoke(Map.of("command", "cat " + keyFile));

        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("secret");
    }

    @Test
    void bashToolShouldUseAllowedCommandsAsDefaultPathCheckedCommands(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello");
        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(), Set.of("cat"), Set.of());

        JsonNode result = localBashTool.invoke(Map.of("command", "cat " + file));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories");
    }

    @Test
    void bashToolShouldAllowCustomConfiguredCommands(@TempDir Path tempDir) {
        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(), Set.of("echo", "ls"), Set.of("ls"));

        JsonNode echoResult = localBashTool.invoke(Map.of("command", "echo hello"));
        assertThat(echoResult.asText()).contains("exitCode: 0");
        assertThat(echoResult.asText()).contains("hello");

        JsonNode catResult = localBashTool.invoke(Map.of("command", "cat somefile"));
        assertThat(catResult.asText()).contains("Command not allowed: cat");
    }

    @Test
    void agentFileCreateToolShouldWriteAgentYamlByDefault(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        PlatformCreateAgent tool = new PlatformCreateAgent(agentsDir);

        JsonNode result = tool.invoke(Map.of(
                "agentId", "qa_bot",
                "description", "QA 助手",
                "modelKey", "openai-gpt35",
                "systemPrompt", "你是 QA 助手\n请先问清问题",
                "mode", "ONESHOT"
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("created").asBoolean()).isTrue();
        assertThat(result.path("agentId").asText()).isEqualTo("qa_bot");
        assertThat(result.path("format").asText()).isEqualTo("yml");

        Path file = agentsDir.resolve("qa_bot.yml");
        assertThat(Files.exists(file)).isTrue();
        JsonNode content = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(Files.readString(file));
        assertThat(content.path("description").asText()).isEqualTo("QA 助手");
        assertThat(content.path("modelConfig").path("modelKey").asText()).isEqualTo("openai-gpt35");
        assertThat(content.path("mode").asText()).isEqualTo("ONESHOT");
        assertThat(content.path("plain").path("systemPrompt").asText()).isEqualTo("你是 QA 助手\n请先问清问题");
        assertThat(content.has("toolConfig")).isFalse();
    }

    @Test
    void agentFileCreateToolShouldWriteAgentJsonWhenRequested(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        PlatformCreateAgent tool = new PlatformCreateAgent(agentsDir);

        JsonNode result = tool.invoke(Map.of(
                "agentId", "qa_json_bot",
                "description", "QA JSON 助手",
                "modelKey", "openai-gpt35",
                "systemPrompt", "json output",
                "mode", "ONESHOT",
                "format", "json"
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("format").asText()).isEqualTo("json");
        assertThat(Files.exists(agentsDir.resolve("qa_json_bot.json"))).isTrue();
        assertThat(Files.exists(agentsDir.resolve("qa_json_bot.yml"))).isFalse();
    }

    @Test
    void agentFileCreateToolShouldWriteIconObject(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        PlatformCreateAgent tool = new PlatformCreateAgent(agentsDir);

        JsonNode result = tool.invoke(Map.of(
                "agentId", "icon_bot",
                "description", "图标对象测试",
                "modelKey", "openai-gpt35",
                "systemPrompt", "你好",
                "mode", "ONESHOT",
                "icon", Map.of("name", "rocket", "color", "#3F7BFA")
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode content = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(Files.readString(agentsDir.resolve("icon_bot.yml")));
        assertThat(content.path("icon").path("name").asText()).isEqualTo("rocket");
        assertThat(content.path("icon").path("color").asText()).isEqualTo("#3F7BFA");
    }

    @Test
    void agentFileCreateToolShouldRejectInvalidAgentId(@TempDir Path tempDir) {
        PlatformCreateAgent tool = new PlatformCreateAgent(tempDir.resolve("agents"));

        JsonNode result = tool.invoke(Map.of("agentId", "../escape"));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).contains("Invalid agentId");
    }

    @Test
    void agentFileCreateToolShouldDefaultModelKeyFromFirstProvider(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        Path modelsDir = tempDir.resolve("models");
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(modelsDir);
        Files.createDirectories(toolsDir);
        Files.writeString(modelsDir.resolve("zhipu-glm-4-plus.json"), """
                {
                  "key": "zhipu-glm-4-plus",
                  "provider": "zhipu",
                  "protocol": "OPENAI",
                  "modelId": "glm-4-plus"
                }
                """);
        Files.writeString(modelsDir.resolve("bailian-qwen3-max.json"), """
                {
                  "key": "bailian-qwen3-max",
                  "provider": "bailian",
                  "protocol": "OPENAI",
                  "modelId": "qwen3-max"
                }
                """);

        AgentProviderProperties providerProperties = new AgentProviderProperties();
        LinkedHashMap<String, AgentProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        AgentProviderProperties.ProviderConfig zhipu = new AgentProviderProperties.ProviderConfig();
        zhipu.setBaseUrl("https://example.com/v1");
        zhipu.setApiKey("test-zhipu-key");
        zhipu.setModel("glm-4-plus");
        providers.put("zhipu", zhipu);
        AgentProviderProperties.ProviderConfig bailian = new AgentProviderProperties.ProviderConfig();
        bailian.setBaseUrl("https://example.com/v1");
        bailian.setApiKey("test-bailian-key");
        bailian.setModel("qwen3-max");
        providers.put("bailian", bailian);
        providerProperties.setProviders(providers);

        ModelProperties modelProperties = new ModelProperties();
        modelProperties.setExternalDir(modelsDir.toString());
        ModelRegistryService modelRegistryService = new ModelRegistryService(
                objectMapper,
                modelProperties,
                providerProperties
        );

        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setExternalDir(agentsDir.toString());
        AgentFileCreateToolProperties toolProperties = new AgentFileCreateToolProperties();
        PlatformCreateAgent tool = new PlatformCreateAgent(
                agentProperties,
                toolProperties,
                providerProperties,
                modelRegistryService
        );

        JsonNode result = tool.invoke(Map.of(
                "agentId", "fortune_bot",
                "description", "算命大师",
                "systemPrompt", "你是算命大师",
                "mode", "ONESHOT"
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode content = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(Files.readString(agentsDir.resolve("fortune_bot.yml")));
        assertThat(content.path("modelConfig").path("modelKey").asText()).isEqualTo("zhipu-glm-4-plus");
        assertThat(content.path("mode").asText()).isEqualTo("ONESHOT");
    }

    @Test
    void agentFileCreateToolShouldUseConfiguredDefaultSystemPrompt(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        PlatformCreateAgent tool = new PlatformCreateAgent(agentsDir, "这是配置的默认提示词");

        JsonNode result = tool.invoke(Map.of(
                "agentId", "default_prompt_bot",
                "modelKey", "bailian-qwen3-max",
                "mode", "ONESHOT"
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode content = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(Files.readString(agentsDir.resolve("default_prompt_bot.yml")));
        assertThat(content.path("plain").path("systemPrompt").asText()).isEqualTo("这是配置的默认提示词");
    }

    @Test
    void agentFileCreateToolShouldUpdateExistingJsonInsteadOfCreatingYaml(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("legacy_bot.json"), """
                {
                  "key": "legacy_bot",
                  "description": "legacy",
                  "modelConfig": {
                    "modelKey": "openai-gpt35"
                  },
                  "mode": "ONESHOT",
                  "plain": {
                    "systemPrompt": "old prompt"
                  }
                }
                """);
        PlatformCreateAgent tool = new PlatformCreateAgent(agentsDir);

        JsonNode result = tool.invoke(Map.of(
                "agentId", "legacy_bot",
                "description", "updated legacy",
                "modelKey", "openai-gpt35",
                "systemPrompt", "new prompt",
                "mode", "ONESHOT"
        ));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("updated").asBoolean()).isTrue();
        assertThat(result.path("format").asText()).isEqualTo("json");
        assertThat(Files.exists(agentsDir.resolve("legacy_bot.json"))).isTrue();
        assertThat(Files.exists(agentsDir.resolve("legacy_bot.yml"))).isFalse();

        JsonNode content = objectMapper.readTree(Files.readString(agentsDir.resolve("legacy_bot.json")));
        assertThat(content.path("description").asText()).isEqualTo("updated legacy");
        assertThat(content.path("plain").path("systemPrompt").asText()).isEqualTo("new prompt");
    }

}
