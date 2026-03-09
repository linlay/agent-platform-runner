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
        assertThat(frontend.toolType()).isEqualTo("html");
        assertThat(frontend.viewportKey()).isEqualTo("confirm_dialog");

        assertThat(action.kind()).isEqualTo(ToolKind.ACTION);
        assertThat(action.toolAction()).isTrue();
        assertThat(action.toolType()).isNull();
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

    private ToolProperties properties(Path toolsDir) {
        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());
        return properties;
    }
}
