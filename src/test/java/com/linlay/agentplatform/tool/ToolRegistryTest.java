package com.linlay.agentplatform.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.service.mcp.McpToolSyncService;
import com.linlay.agentplatform.config.ToolProperties;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeTool dateTimeTool = new DateTimeTool();
    private final SystemBash bashTool = TestSystemBashFactory.defaultBash();

    @Test
    void dateTimeShouldDefaultToSystemTimezone() {
        JsonNode result = dateTimeTool.invoke(Map.of());

        assertThat(result.path("timezone").asText()).isEqualTo(ZoneId.systemDefault().getId());
        assertThat(result.path("timezoneOffset").asText()).isNotBlank();
        assertThat(result.path("offset").asText()).isEqualTo("0");
        assertThat(result.path("source").asText()).isEqualTo("system-clock");
        assertThat(result.path("date").asText()).isNotBlank();
        assertThat(result.path("weekday").asText()).startsWith("星期");
        assertThat(result.path("lunarDate").asText()).isNotBlank();
        assertThat(result.path("time").asText()).isNotBlank();
        assertThatCode(() -> ZonedDateTime.parse(result.path("iso").asText())).doesNotThrowAnyException();
    }

    @Test
    void dateTimeShouldSupportExplicitTimezoneAndOffset() {
        JsonNode result = dateTimeTool.invoke(Map.of(
                "timezone", "UTC+8",
                "offset", "+1D-3H+20m"
        ));

        assertThat(result.path("timezone").asText()).isEqualTo("+08:00");
        assertThat(result.path("timezoneOffset").asText()).isEqualTo("UTC+8");
        assertThat(result.path("offset").asText()).isEqualTo("+1D-3H+20m");
        assertThat(result.path("lunarDate").asText()).isNotBlank();
        assertThatCode(() -> ZonedDateTime.parse(result.path("iso").asText())).doesNotThrowAnyException();
    }

    @Test
    void dateTimeShouldApplyChainedOffsetInOrder() {
        ZonedDateTime base = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "0"
        )).path("iso").asText());
        ZonedDateTime shifted = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "+1D-3H+20m"
        )).path("iso").asText());

        ZonedDateTime expected = base.plusDays(1).minusHours(3).plusMinutes(20);
        assertThat(Duration.between(expected.toInstant(), shifted.toInstant()).abs().toSeconds()).isLessThanOrEqualTo(2);
    }

    @Test
    void dateTimeShouldApplyMonthOffsetWithUppercaseM() {
        ZonedDateTime base = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "0"
        )).path("iso").asText());
        ZonedDateTime shifted = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "+10M+25D"
        )).path("iso").asText());

        ZonedDateTime expected = base.plusMonths(10).plusDays(25);
        assertThat(Duration.between(expected.toInstant(), shifted.toInstant()).abs().toSeconds()).isLessThanOrEqualTo(2);
    }

    @Test
    void dateTimeShouldApplyMixedMonthAndMinuteOffsetsInOrder() {
        ZonedDateTime base = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "0"
        )).path("iso").asText());
        ZonedDateTime shifted = ZonedDateTime.parse(dateTimeTool.invoke(Map.of(
                "timezone", "UTC",
                "offset", "+1M-15m"
        )).path("iso").asText());

        ZonedDateTime expected = base.plusMonths(1).minusMinutes(15);
        assertThat(Duration.between(expected.toInstant(), shifted.toInstant()).abs().toSeconds()).isLessThanOrEqualTo(2);
    }

    @Test
    void dateTimeShouldRejectInvalidTimezoneOrOffset() {
        assertThatThrownBy(() -> dateTimeTool.invoke(Map.of("timezone", "Mars/Base")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid timezone");
        assertThatThrownBy(() -> dateTimeTool.invoke(Map.of("offset", "1D")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid offset");
        assertThatThrownBy(() -> dateTimeTool.invoke(Map.of("offset", "+1Q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid offset");
    }

    @Test
    void backendToolMetadataShouldOverrideNativeToolDefinition(@TempDir Path tempDir) throws IOException {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("datetime.yml"), """
                name: datetime
                label: 日期时间
                description: city datetime from backend
                type: function
                afterCallHint: use city datetime prompt
                inputSchema:
                  type: object
                  properties:
                    timezone:
                      type: string
                    offset:
                      type: string
                  required:
                    - timezone
                  additionalProperties: false
                """);

        ToolFileRegistryService toolFileRegistryService = new ToolFileRegistryService(
                new ObjectMapper(),
                new PathMatchingResourcePatternResolver(),
                toolsDir
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolFileRegistryService", toolFileRegistryService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(new DateTimeTool()),
                beanFactory.getBeanProvider(ToolFileRegistryService.class)
        );

        BaseTool dateTimeMetadataTool = toolRegistry.list().stream()
                .filter(tool -> "datetime".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        assertThat(dateTimeMetadataTool.description()).isEqualTo("city datetime from backend");
        assertThat(dateTimeMetadataTool.afterCallHint()).isEqualTo("use city datetime prompt");
        assertThat(dateTimeMetadataTool.parametersSchema().get("required")).isEqualTo(List.of("timezone"));
        JsonNode result = toolRegistry.invoke("datetime", Map.of("timezone", "Asia/Shanghai"));
        assertThat(result.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(result.path("timezoneOffset").asText()).isEqualTo("UTC+8");
    }

    @Test
    void backendMetadataShouldKeepRuntimeDescriptionForNativeBashTool(@TempDir Path tempDir) throws IOException {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("_bash_.yml"), """
                name: _bash_
                label: Bash命令执行
                description: static backend bash description
                type: function
                inputSchema:
                  type: object
                  properties:
                    command:
                      type: string
                  required:
                    - command
                  additionalProperties: false
                """);

        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir);
        SystemBash bash = TestSystemBashFactory.bash(
                projectDir,
                List.of(projectDir),
                Set.of("pwd"),
                Set.of("pwd")
        );

        ToolFileRegistryService toolFileRegistryService = new ToolFileRegistryService(
                new ObjectMapper(),
                new PathMatchingResourcePatternResolver(),
                toolsDir
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolFileRegistryService", toolFileRegistryService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(bash),
                beanFactory.getBeanProvider(ToolFileRegistryService.class)
        );

        BaseTool bashMetadataTool = toolRegistry.list().stream()
                .filter(tool -> "_bash_".equals(tool.name()))
                .findFirst()
                .orElseThrow();

        assertThat(bashMetadataTool.description()).contains("workingDirectory: " + projectDir.toAbsolutePath().normalize());
        assertThat(toolRegistry.description("_bash_")).contains("shellFeaturesEnabled: false");
        assertThat(toolRegistry.descriptor("_bash_"))
                .isPresent()
                .get()
                .extracting(ToolDescriptor::label)
                .isEqualTo("Bash命令执行");
    }

    @Test
    void disabledContainerHubShouldBeHiddenEvenWhenYamlMetadataExists(@TempDir Path tempDir) throws IOException {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("sandbox_bash.yml"), """
                name: sandbox_bash
                label: 执行命令（沙箱）
                description: hidden when disabled
                type: function
                inputSchema:
                  type: object
                  properties:
                    command:
                      type: string
                  required:
                    - command
                  additionalProperties: false
                """);

        ToolFileRegistryService toolFileRegistryService = new ToolFileRegistryService(
                new ObjectMapper(),
                new PathMatchingResourcePatternResolver(),
                toolsDir
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolFileRegistryService", toolFileRegistryService);

        ContainerHubToolProperties containerHubProperties = new ContainerHubToolProperties();
        containerHubProperties.setEnabled(false);

        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class),
                containerHubProperties
        );

        assertThat(toolRegistry.list().stream().map(BaseTool::name)).doesNotContain("sandbox_bash");
        assertThat(toolRegistry.descriptor("sandbox_bash")).isEmpty();
        assertThat(toolRegistry.description("sandbox_bash")).isEmpty();
        assertThat(toolRegistry.label("sandbox_bash")).isNull();
    }

    @Test
    void enabledContainerHubShouldExposeLocalDescriptorEvenWhenYamlMetadataExists(@TempDir Path tempDir) throws IOException {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("sandbox_bash.yml"), """
                name: sandbox_bash
                label: 执行命令（沙箱）
                description: metadata from yaml
                type: function
                inputSchema:
                  type: object
                  properties:
                    command:
                      type: string
                  required:
                    - command
                  additionalProperties: false
                """);

        ToolFileRegistryService toolFileRegistryService = new ToolFileRegistryService(
                new ObjectMapper(),
                new PathMatchingResourcePatternResolver(),
                toolsDir
        );

        ContainerHubToolProperties containerHubProperties = new ContainerHubToolProperties();
        containerHubProperties.setEnabled(true);
        containerHubProperties.setBaseUrl("http://127.0.0.1:11960");
        SystemContainerHubBash containerHubTool = new SystemContainerHubBash(
                containerHubProperties,
                new ContainerHubClient(containerHubProperties, objectMapper)
        );

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolFileRegistryService", toolFileRegistryService);

        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(containerHubTool),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class),
                containerHubProperties
        );

        assertThat(toolRegistry.descriptor("sandbox_bash"))
                .isPresent()
                .get()
                .satisfies(descriptor -> {
                    assertThat(descriptor.sourceType()).isEqualTo("agent-local");
                    assertThat(descriptor.sourceKey()).isNull();
                    assertThat(descriptor.description()).contains("在沙箱容器中执行命令");
                });
    }

    @Test
    void mcpToolShouldAppearAsVirtualTool() {
        ToolDescriptor mcpDescriptor = new ToolDescriptor(
                "mock.weather.query",
                null,
                "mock weather",
                "use weather viewport card",
                Map.of("type", "object"),
                false,
                true,
                false,
                null,
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
                List.of(new DateTimeTool()),
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
                "datetime",
                null,
                "remote city time",
                "",
                Map.of("type", "object"),
                false,
                true,
                false,
                null,
                "mcp",
                "mock",
                null,
                "mcp://mock"
        );
        McpToolSyncService mcpToolSyncService = mock(McpToolSyncService.class);
        when(mcpToolSyncService.list()).thenReturn(List.of(conflictDescriptor));
        when(mcpToolSyncService.find("datetime")).thenReturn(java.util.Optional.of(conflictDescriptor));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("mcpToolSyncService", mcpToolSyncService);
        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(new DateTimeTool()),
                beanFactory.getBeanProvider(ToolFileRegistryService.class),
                beanFactory.getBeanProvider(McpToolSyncService.class)
        );

        assertThat(toolRegistry.descriptor("datetime"))
                .isPresent()
                .get()
                .extracting(ToolDescriptor::sourceType)
                .isEqualTo("local");
        JsonNode result = toolRegistry.invoke("datetime", Map.of("timezone", "Asia/Shanghai"));
        assertThat(result.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(result.path("timezoneOffset").asText()).isEqualTo("UTC+8");
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
    void bashToolShouldAllowRelativePathInsideWorkingDirectoryByDefault(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello");
        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(), Set.of("cat"), Set.of());

        JsonNode result = localBashTool.invoke(Map.of("command", "cat demo.txt"));

        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("hello");
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

        assertThat(result.asText()).contains("exitCode: 0");
        assertThat(result.asText()).contains("hello");
    }

    @Test
    void bashToolShouldStillRejectPathOutsideWorkingDirectoryWhenNoExtraAllowedPath(@TempDir Path tempDir) throws IOException {
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("demo.txt"), "hello");

        SystemBash localBashTool = TestSystemBashFactory.bash(tempDir, List.of(), Set.of("cat"), Set.of());

        JsonNode result = localBashTool.invoke(Map.of("command", "cat ../outside/demo.txt"));

        assertThat(result.asText()).contains("exitCode: -1");
        assertThat(result.asText()).contains("Path not allowed outside authorized directories: ../outside/demo.txt");
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

}
