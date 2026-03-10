package com.linlay.agentplatform.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.service.CatalogDiff;

class ToolFileRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadBackendFrontendAndActionToolsFromToolsDirectory() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("bash.yml"), """
                type: function
                name: bash
                description: bash tool
                afterCallHint: bash prompt
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("confirm_dialog.yml"), """
                type: function
                name: confirm_dialog
                label: 确认弹窗
                description: confirm
                toolType: html
                viewportKey: confirm_dialog
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("switch_theme.yml"), """
                type: function
                name: switch_theme
                description: switch
                toolAction: true
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        ToolDescriptor backend = service.find("bash").orElseThrow();
        ToolDescriptor frontend = service.find("confirm_dialog").orElseThrow();
        ToolDescriptor action = service.find("switch_theme").orElseThrow();

        assertThat(backend.kind()).isEqualTo(ToolKind.BACKEND);
        assertThat(backend.toolType()).isNull();
        assertThat(backend.afterCallHint()).isEqualTo("bash prompt");
        assertThat(backend.clientVisible()).isTrue();

        assertThat(frontend.kind()).isEqualTo(ToolKind.FRONTEND);
        assertThat(frontend.label()).isEqualTo("确认弹窗");
        assertThat(frontend.toolType()).isEqualTo("html");
        assertThat(frontend.viewportKey()).isEqualTo("confirm_dialog");
        assertThat(frontend.requiresFrontendSubmit()).isTrue();

        assertThat(action.kind()).isEqualTo(ToolKind.ACTION);
        assertThat(action.toolAction()).isTrue();
        assertThat(action.toolType()).isNull();
    }

    @Test
    void shouldSkipInvalidToolMetadataShapes() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("missing_schema.yml"), """
                type: function
                name: missing_schema
                description: missing schema
                """);
        Files.writeString(toolsDir.resolve("blank_label.yml"), """
                type: function
                name: blank_label
                label: ""
                description: blank label
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("tool_type_only.yml"), """
                type: function
                name: tool_type_only
                description: invalid frontend metadata
                toolType: html
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("action_and_viewport.yml"), """
                type: function
                name: action_and_viewport
                description: invalid action metadata
                toolAction: true
                toolType: html
                viewportKey: dialog
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        assertThat(service.find("missing_schema")).isEmpty();
        assertThat(service.find("blank_label")).isEmpty();
        assertThat(service.find("tool_type_only")).isEmpty();
        assertThat(service.find("action_and_viewport")).isEmpty();
    }

    @Test
    void shouldPreserveDeclaredNameCaseWhileLookupRemainsCaseInsensitive() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("mixed_case.yml"), """
                type: function
                name: Weather.Query
                label: 天气查询
                description: mixed case name
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        ToolDescriptor descriptor = service.find("weather.query").orElseThrow();
        assertThat(descriptor.name()).isEqualTo("Weather.Query");
        assertThat(descriptor.key()).isEqualTo("weather.query");
        assertThat(service.find("WEATHER.QUERY")).isPresent();
    }

    @Test
    void shouldSkipConflictedToolNames() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("a.yml"), """
                type: function
                name: dup_name
                description: a
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("b.json"), """
                {
                  "type": "function",
                  "name": "dup_name",
                  "description": "b",
                  "inputSchema": {"type": "object"}
                }
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        assertThat(service.find("dup_name")).isEmpty();
    }

    @Test
    void shouldSkipLegacyMultiToolFiles() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("legacy.yml"), """
                tools:
                  - type: function
                    name: legacy_tool
                    description: legacy
                    inputSchema:
                      type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        assertThat(service.find("legacy_tool")).isEmpty();
    }

    @Test
    void shouldLoadYamlToolContentAndClientVisibleFlag() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("hidden_yaml.yml"), """
                type: function
                name: hidden_yaml_tool
                description: hidden yaml tool
                clientVisible: false
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        ToolDescriptor descriptor = service.find("hidden_yaml_tool").orElseThrow();
        assertThat(descriptor.kind()).isEqualTo(ToolKind.BACKEND);
        assertThat(descriptor.clientVisible()).isFalse();
    }

    @Test
    void shouldReturnCatalogDiffWhenCapabilitiesChanged() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("a.yml"), """
                type: function
                name: a_tool
                description: a
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        Files.writeString(toolsDir.resolve("b.yml"), """
                type: function
                name: b_tool
                description: b
                inputSchema:
                  type: object
                """);
        CatalogDiff diff = service.refreshTools();

        assertThat(diff.addedKeys()).contains("b_tool");
        assertThat(diff.changedKeys()).contains("b_tool");
    }

    @Test
    void shouldLoadTerminalCommandReviewExampleTool() {
        Path toolsDir = Path.of("example/tools").toAbsolutePath().normalize();

        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties(toolsDir));

        ToolDescriptor descriptor = service.find("terminal_command_review").orElseThrow();
        assertThat(descriptor.kind()).isEqualTo(ToolKind.FRONTEND);
        assertThat(descriptor.label()).isEqualTo("命令审查面板");
        assertThat(descriptor.toolType()).isEqualTo("html");
        assertThat(descriptor.viewportKey()).isEqualTo("terminal_command_review");
        assertThat(descriptor.description()).contains("命令清单");
    }

    private ToolProperties properties(Path toolsDir) {
        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());
        return properties;
    }
}
