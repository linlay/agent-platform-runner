package com.linlay.agentplatform.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.util.CatalogDiff;

class ToolFileRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadBackendFrontendAndActionToolsFromToolsDirectory() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("bash.yml"), backendToolYaml("bash", "Bash", "bash tool", "bash prompt"));
        Files.writeString(toolsDir.resolve("confirm_dialog.yml"), frontendToolYaml("confirm_dialog", "确认弹窗", "confirm", "html", "confirm_dialog"));
        Files.writeString(toolsDir.resolve("switch_theme.yml"), actionToolYaml("switch_theme", "切换主题", "switch"));

        ToolFileRegistryService service = service(toolsDir);

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
                name: missing_schema
                label: Missing Schema
                description: missing schema
                type: function
                """);
        Files.writeString(toolsDir.resolve("blank_label.yml"), """
                name: blank_label
                label: ""
                description: blank label
                type: function
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("tool_type_only.yml"), """
                name: tool_type_only
                label: Tool Type Only
                description: invalid frontend metadata
                type: function
                toolType: html
                inputSchema:
                  type: object
                """);
        Files.writeString(toolsDir.resolve("action_and_viewport.yml"), """
                name: action_and_viewport
                label: Action And Viewport
                description: invalid action metadata
                type: function
                toolAction: true
                toolType: html
                viewportKey: dialog
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

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
                name: Weather.Query
                label: 天气查询
                description: mixed case name
                type: function
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

        ToolDescriptor descriptor = service.find("weather.query").orElseThrow();
        assertThat(descriptor.name()).isEqualTo("Weather.Query");
        assertThat(descriptor.key()).isEqualTo("weather.query");
        assertThat(service.find("WEATHER.QUERY")).isPresent();
    }

    @Test
    void shouldIgnoreLegacyJsonToolFiles() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("legacy.json"), "{\"name\":\"legacy\"}");
        Files.writeString(toolsDir.resolve("valid.yml"), backendToolYaml("bash", "Bash", "bash tool", "bash prompt"));

        ToolFileRegistryService service = service(toolsDir);

        assertThat(service.find("legacy")).isEmpty();
        assertThat(service.find("bash")).isPresent();
    }

    @Test
    void shouldSkipLegacyMultiToolFiles() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("legacy.yml"), """
                name: legacy_tool
                label: Legacy Tool
                description: legacy
                type: function
                tools:
                  - type: function
                    name: ignored
                    description: ignored
                    inputSchema:
                      type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

        assertThat(service.find("legacy_tool")).isEmpty();
    }

    @Test
    void shouldLoadYamlToolContentAndClientVisibleFlag() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("hidden_yaml.yml"), """
                name: hidden_yaml_tool
                label: Hidden YAML Tool
                description: hidden yaml tool
                type: function
                clientVisible: false
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

        ToolDescriptor descriptor = service.find("hidden_yaml_tool").orElseThrow();
        assertThat(descriptor.kind()).isEqualTo(ToolKind.BACKEND);
        assertThat(descriptor.clientVisible()).isFalse();
    }

    @Test
    void shouldLoadAllPlanToolsAsClientInvisibleFromClasspath() {
        ToolFileRegistryService service = new ToolFileRegistryService(new ObjectMapper());

        assertThat(service.find("_plan_add_tasks_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
        assertThat(service.find("_plan_update_task_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
        assertThat(service.find("_plan_get_tasks_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
        assertThat(service.find("_memory_write_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
        assertThat(service.find("_memory_read_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
        assertThat(service.find("_memory_search_"))
                .map(ToolDescriptor::clientVisible)
                .contains(false);
    }

    @Test
    void shouldIgnoreScaffoldToolPlaceholder() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);

        Files.writeString(toolsDir.resolve("custom_tool.yml"), """
                scaffold: true
                name: custom_tool
                label: Custom Tool
                description: scaffold placeholder
                type: function
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

        assertThat(service.find("custom_tool")).isEmpty();
        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldReturnCatalogDiffWhenCapabilitiesChanged() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("a.yml"), """
                name: a_tool
                label: A Tool
                description: a
                type: function
                inputSchema:
                  type: object
                """);

        ToolFileRegistryService service = service(toolsDir);

        Files.writeString(toolsDir.resolve("b.yml"), """
                name: b_tool
                label: B Tool
                description: b
                type: function
                inputSchema:
                  type: object
                """);
        CatalogDiff diff = service.refreshTools();

        assertThat(diff.addedKeys()).contains("b_tool");
        assertThat(diff.changedKeys()).contains("b_tool");
    }

    @Test
    void shouldLoadToolsFromNestedDirectories() throws Exception {
        Path toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir.resolve("nested/forms"));
        Files.writeString(toolsDir.resolve("nested/forms/confirm_dialog.yml"), frontendToolYaml("confirm_dialog", "确认弹窗", "confirm", "html", "confirm_dialog"));

        ToolFileRegistryService service = service(toolsDir);

        assertThat(service.find("confirm_dialog")).isPresent();
    }

    private ToolFileRegistryService service(Path toolsDir) {
        return new ToolFileRegistryService(new ObjectMapper(), new PathMatchingResourcePatternResolver(), toolsDir);
    }

    private String backendToolYaml(String name, String label, String description, String afterCallHint) {
        return """
                name: %s
                label: %s
                description: %s
                type: function
                afterCallHint: %s
                inputSchema:
                  type: object
                """.formatted(name, label, description, afterCallHint);
    }

    private String frontendToolYaml(String name, String label, String description, String toolType, String viewportKey) {
        return """
                name: %s
                label: %s
                description: %s
                type: function
                toolType: %s
                viewportKey: %s
                inputSchema:
                  type: object
                """.formatted(name, label, description, toolType, viewportKey);
    }

    private String actionToolYaml(String name, String label, String description) {
        return """
                name: %s
                label: %s
                description: %s
                type: function
                toolAction: true
                inputSchema:
                  type: object
                """.formatted(name, label, description);
    }
}
