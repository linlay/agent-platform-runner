package com.linlay.agentplatform.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadDefaultAgentKeyFromTeamFile() throws Exception {
        Files.writeString(tempDir.resolve("a1b2c3d4e5f6.json"), """
                {
                  "name": "Default Team",
                  "defaultAgentKey": "demoModeReact",
                  "agentKeys": ["demoModeReact", "demoModePlain"]
                }
                """);

        TeamProperties properties = new TeamProperties();
        properties.setExternalDir(tempDir.toString());
        TeamRegistryService service = new TeamRegistryService(new ObjectMapper(), properties);

        TeamDescriptor descriptor = service.find("a1b2c3d4e5f6").orElseThrow();
        assertThat(descriptor.defaultAgentKey()).isEqualTo("demoModeReact");
        assertThat(descriptor.agentKeys()).containsExactly("demoModeReact", "demoModePlain");
    }
}
