package com.linlay.springaiagw.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final MockCityDateTimeTool cityDateTimeTool = new MockCityDateTimeTool();
    private final MockCityWeatherTool cityWeatherTool = new MockCityWeatherTool();
    private final BashTool bashTool = new BashTool();

    @Test
    void sameArgsShouldReturnSameWeatherJson() {
        Map<String, Object> args = Map.of("city", "Shanghai", "date", "2026-02-09");

        JsonNode first = cityWeatherTool.invoke(args);
        JsonNode second = cityWeatherTool.invoke(args);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void sameArgsShouldReturnSameDateTimeJson() {
        Map<String, Object> args = Map.of("city", "Shanghai");

        JsonNode first = cityDateTimeTool.invoke(args);
        JsonNode second = cityDateTimeTool.invoke(args);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void bashToolShouldRejectUnlistedCommand() {
        JsonNode result = bashTool.invoke(Map.of("command", "cat /etc/passwd"));
        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).contains("outside working directory");
    }

    @Test
    void bashToolShouldRunAllowedLsCommand() {
        JsonNode result = bashTool.invoke(Map.of("command", "ls"));
        assertThat(result.path("tool").asText()).isEqualTo("bash");
        assertThat(result.path("exitCode").asInt()).isEqualTo(0);
    }

    @Test
    void bashToolShouldReadLocalFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("demo.txt");
        Files.writeString(file, "hello");
        BashTool localBashTool = new BashTool(tempDir);

        JsonNode result = localBashTool.invoke(Map.of("command", "cat demo.txt"));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("stdout").asText()).contains("hello");
    }

    @Test
    void bashToolShouldExpandGlobForCat(@TempDir Path tempDir) throws IOException {
        Path agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("a.json"), "{\"name\":\"a\"}\n");
        Files.writeString(agentsDir.resolve("b.json"), "{\"name\":\"b\"}\n");
        BashTool localBashTool = new BashTool(tempDir);

        JsonNode result = localBashTool.invoke(Map.of("command", "cat agents/*"));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("stdout").asText()).contains("\"name\":\"a\"");
        assertThat(result.path("stdout").asText()).contains("\"name\":\"b\"");
    }
}
