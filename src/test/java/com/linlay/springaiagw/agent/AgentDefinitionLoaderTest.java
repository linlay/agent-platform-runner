package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadExternalAgentByFileName() throws IOException {
        Path file = tempDir.resolve("ops_daily.json");
        Files.writeString(file, """
                {
                  "providerType":"BAILIAN",
                  "model":"qwen3-max",
                  "systemPrompt":"你是运维助手",
                  "mode":"THINKING_AND_CONTENT_WITH_DUAL_TOOL_CALLS",
                  "defaultCity":"Shanghai",
                  "defaultBashCommand":"df -h"
                }
                """);

        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setExternalDir(tempDir.toString());

        AgentDefinitionLoader loader = new AgentDefinitionLoader(new ObjectMapper(), properties);

        Map<String, AgentDefinition> byId = loader.loadAll().stream()
                .collect(Collectors.toMap(AgentDefinition::id, definition -> definition));

        assertThat(byId).containsKey("ops_daily");
        assertThat(byId.get("ops_daily").mode()).isEqualTo(AgentMode.THINKING_AND_CONTENT_WITH_DUAL_TOOL_CALLS);
        assertThat(byId.get("ops_daily").deepThink()).isTrue();
        assertThat(byId.get("ops_daily").systemPrompt()).isEqualTo("你是运维助手");
    }
}
