package com.linlay.agentplatform.catalog.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.TeamProperties;
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
        Files.writeString(tempDir.resolve("a1b2c3d4e5f6.yml"), """
                name: Default Team
                defaultAgentKey: demoModeReact
                agentKeys:
                  - demoModeReact
                  - demoModePlain
                """);

        TeamProperties properties = new TeamProperties();
        properties.setExternalDir(tempDir.toString());
        TeamRegistryService service = new TeamRegistryService(new ObjectMapper(), properties);

        TeamDescriptor descriptor = service.find("a1b2c3d4e5f6").orElseThrow();
        assertThat(descriptor.defaultAgentKey()).isEqualTo("demoModeReact");
        assertThat(descriptor.agentKeys()).containsExactly("demoModeReact", "demoModePlain");
    }

    @Test
    void shouldIgnoreLegacyJsonTeamFile() throws Exception {
        Files.writeString(tempDir.resolve("a1b2c3d4e5f6.json"), "{\"name\":\"legacy\"}");
        Files.writeString(tempDir.resolve("a1b2c3d4e5f6.yml"), """
                name: Default Team
                defaultAgentKey: demoModeReact
                """);

        TeamProperties properties = new TeamProperties();
        properties.setExternalDir(tempDir.toString());
        TeamRegistryService service = new TeamRegistryService(new ObjectMapper(), properties);

        assertThat(service.find("a1b2c3d4e5f6")).isPresent();
    }

    @Test
    void shouldLoadTeamFromNestedDirectory() throws Exception {
        Path nestedDir = tempDir.resolve("groups");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("a1b2c3d4e5f6.yml"), """
                name: Default Team
                defaultAgentKey: demoModeReact
                """);

        TeamProperties properties = new TeamProperties();
        properties.setExternalDir(tempDir.toString());
        TeamRegistryService service = new TeamRegistryService(new ObjectMapper(), properties);

        assertThat(service.find("a1b2c3d4e5f6")).isPresent();
    }

    @Test
    void shouldIgnoreExampleTeamAndLoadDemoTeam() throws Exception {
        Files.writeString(tempDir.resolve("deadbeefcafe.example.yml"), """
                name: Example Team
                defaultAgentKey: demoModeReact
                """);
        Files.writeString(tempDir.resolve("feedfacecafe.demo.yml"), """
                name: Demo Team
                defaultAgentKey: demoModePlain
                """);

        TeamProperties properties = new TeamProperties();
        properties.setExternalDir(tempDir.toString());
        TeamRegistryService service = new TeamRegistryService(new ObjectMapper(), properties);

        assertThat(service.find("deadbeefcafe")).isEmpty();
        assertThat(service.find("feedfacecafe")).isPresent();
    }
}
