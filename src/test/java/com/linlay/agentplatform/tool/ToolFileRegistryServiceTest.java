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
    void shouldLoadBackendAndActionCapabilitiesFromToolsDirectory() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("bash.backend"), """
                {
                  "tools": [
                    {"type":"function", "name":"bash", "description":"bash tool", "afterCallHint":"bash prompt", "parameters":{"type":"object"}}
                  ]
                }
                """);
        Files.writeString(toolsDir.resolve("switch_theme.action"), """
                {
                  "tools": [
                    {"type":"function", "name":"switch_theme", "description":"switch", "parameters":{"type":"object"}}
                  ]
                }
                """);
        Files.writeString(toolsDir.resolve("launch_fireworks.action"), """
                {
                  "tools": [
                    {"type":"function", "name":"launch_fireworks", "description":"fireworks", "parameters":{"type":"object"}}
                  ]
                }
                """);
        Files.writeString(toolsDir.resolve("show_modal.action"), """
                {
                  "tools": [
                    {"type":"function", "name":"show_modal", "description":"modal", "parameters":{"type":"object"}}
                  ]
                }
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());

        ToolFileRegistryService service = new ToolFileRegistryService(
                new ObjectMapper(),
                properties
        );

        ToolDescriptor backend = service.find("bash").orElseThrow();
        ToolDescriptor action = service.find("switch_theme").orElseThrow();
        ToolDescriptor fireworks = service.find("launch_fireworks").orElseThrow();
        ToolDescriptor modal = service.find("show_modal").orElseThrow();

        assertThat(backend.kind()).isEqualTo(ToolKind.BACKEND);
        assertThat(backend.toolType()).isEqualTo("function");
        assertThat(backend.afterCallHint()).isEqualTo("bash prompt");
        assertThat(backend.clientVisible()).isTrue();
        assertThat(service.find("show_weather_card")).isEmpty();

        assertThat(action.kind()).isEqualTo(ToolKind.ACTION);
        assertThat(action.toolType()).isEqualTo("action");
        assertThat(fireworks.kind()).isEqualTo(ToolKind.ACTION);
        assertThat(fireworks.toolType()).isEqualTo("action");
        assertThat(modal.kind()).isEqualTo(ToolKind.ACTION);
        assertThat(modal.toolType()).isEqualTo("action");
    }

    @Test
    void shouldSkipConflictedCapabilityNames() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("a.backend"), """
                {
                  "tools": [
                    {"type":"function", "name":"dup_name", "description":"a", "parameters":{"type":"object"}}
                  ]
                }
                """);
        Files.writeString(toolsDir.resolve("b.action"), """
                {
                  "tools": [
                    {"type":"function", "name":"dup_name", "description":"b", "parameters":{"type":"object"}}
                  ]
                }
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());

        ToolFileRegistryService service = new ToolFileRegistryService(
                new ObjectMapper(),
                properties
        );

        assertThat(service.find("dup_name")).isEmpty();
    }

    @Test
    void shouldOnlyRecognizeFrontendSuffix() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("show_form.frontend"), """
                {
                  "tools": [
                    {"type":"function", "name":"show_form_frontend", "description":"frontend", "parameters":{"type":"object"}}
                  ]
                }
                """);
        Files.writeString(toolsDir.resolve("legacy_html.html"), """
                {
                  "tools": [
                    {"type":"function", "name":"legacy_html_frontend", "description":"legacy html", "parameters":{"type":"object"}}
                  ]
                }
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());

        ToolFileRegistryService service = new ToolFileRegistryService(
                new ObjectMapper(),
                properties
        );

        ToolDescriptor frontend = service.find("show_form_frontend").orElseThrow();
        assertThat(frontend.kind()).isEqualTo(ToolKind.FRONTEND);
        assertThat(frontend.toolType()).isEqualTo("frontend");
        assertThat(frontend.viewportKey()).isEqualTo("show_form");

        assertThat(service.find("legacy_html_frontend")).isEmpty();
    }

    @Test
    void shouldLoadYamlCapabilityContentAndClientVisibleFlag() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("hidden_yaml.backend"), """
                tools:
                  - type: function
                    name: hidden_yaml_tool
                    description: hidden yaml tool
                    clientVisible: false
                    parameters:
                      type: object
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());

        ToolFileRegistryService service = new ToolFileRegistryService(
                new ObjectMapper(),
                properties
        );

        ToolDescriptor descriptor = service.find("hidden_yaml_tool").orElseThrow();
        assertThat(descriptor.kind()).isEqualTo(ToolKind.BACKEND);
        assertThat(descriptor.clientVisible()).isFalse();
    }

    @Test
    void shouldReturnCatalogDiffWhenCapabilitiesChanged() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("a.backend"), """
                { "tools": [ {"type":"function", "name":"a_tool", "description":"a", "parameters":{"type":"object"}} ] }
                """);

        ToolProperties properties = new ToolProperties();
        properties.setExternalDir(toolsDir.toString());
        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper(), properties);

        Files.writeString(toolsDir.resolve("b.backend"), """
                { "tools": [ {"type":"function", "name":"b_tool", "description":"b", "parameters":{"type":"object"}} ] }
                """);
        CatalogDiff diff = service.refreshTools();

        assertThat(diff.addedKeys()).contains("b_tool");
        assertThat(diff.changedKeys()).contains("b_tool");
    }

}
