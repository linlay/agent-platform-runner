package com.linlay.agentplatform.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class McpServerRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadServerFromRegistryFile() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: mock
                baseUrl: http://dynamic-host:18080
                endpointPath: /mcp
                headers:
                  x-dynamic: "1"
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        McpServerRegistryService.RegisteredServer server = service.find("mock").orElseThrow();
        assertThat(server.baseUrl()).isEqualTo("http://dynamic-host:18080");
        assertThat(server.headers()).containsEntry("x-dynamic", "1");
    }

    @Test
    void shouldLoadAliasMapAndPerServerReadTimeoutFromRegistryFile() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: mock
                baseUrl: http://localhost:18080
                endpointPath: /mcp
                readTimeoutMs: 19000
                aliasMap:
                  legacy_weather: mock.weather.query
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        McpServerRegistryService.RegisteredServer server = service.find("mock").orElseThrow();
        assertThat(server.aliasMap()).containsEntry("legacy_weather", "mock.weather.query");
        assertThat(server.readTimeoutMs()).isEqualTo(19000);
        assertThat(server.endpointUrl()).isEqualTo("http://localhost:18080/mcp");
    }

    @Test
    void shouldUseDefaultReadTimeoutWhenRegistryFileDoesNotProvideIt() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: mock
                baseUrl: http://localhost:18080
                endpointPath: /mcp
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        McpServerRegistryService.RegisteredServer server = service.find("mock").orElseThrow();
        assertThat(server.readTimeoutMs()).isEqualTo(15000);
    }

    @Test
    void shouldIncreaseRegistryVersionAfterRefresh() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: mock
                baseUrl: http://localhost:18080
                endpointPath: /mcp
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());
        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);

        long firstVersion = service.currentVersion();
        service.refreshServers();
        long secondVersion = service.currentVersion();
        service.refreshServers();
        long thirdVersion = service.currentVersion();

        assertThat(secondVersion).isGreaterThan(firstVersion);
        assertThat(thirdVersion).isGreaterThan(secondVersion);
    }

    @Test
    void shouldLoadServerFromNestedRegistryDirectory() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir.resolve("nested"));
        Files.writeString(registryDir.resolve("nested/mock.yml"), """
                serverKey: mock
                baseUrl: http://nested-host:18080
                endpointPath: /mcp
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        assertThat(service.find("mock")).isPresent();
    }
}
