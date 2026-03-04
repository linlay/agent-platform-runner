package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deprecatedServersPropertyShouldBeIgnored() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.json"), """
                {
                  "serverKey": "mock",
                  "baseUrl": "http://dynamic-host:18080",
                  "endpointPath": "/mcp",
                  "headers": {
                    "x-dynamic": "1"
                  }
                }
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());
        McpProperties.Server staticServer = new McpProperties.Server();
        staticServer.setServerKey("mock");
        staticServer.setBaseUrl("http://static-host:28080");
        staticServer.setEndpointPath("/mcp");
        staticServer.setHeaders(Map.of("x-static", "1"));
        properties.setServers(List.of(staticServer));

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        McpServerRegistryService.RegisteredServer server = service.find("mock").orElseThrow();
        assertThat(server.baseUrl()).isEqualTo("http://dynamic-host:18080");
        assertThat(server.headers()).containsEntry("x-dynamic", "1");
        assertThat(server.headers()).doesNotContainKey("x-static");
    }

    @Test
    void shouldLoadAliasMapAndPerServerReadTimeoutFromRegistryFile() throws Exception {
        Path registryDir = tempDir.resolve("mcp-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.json"), """
                {
                  "serverKey": "mock",
                  "baseUrl": "http://localhost:18080",
                  "readTimeoutMs": 19000,
                  "aliasMap": {
                    "legacy_weather": "mock.weather.query"
                  }
                }
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
        Files.writeString(registryDir.resolve("mock.json"), """
                {
                  "serverKey": "mock",
                  "baseUrl": "http://localhost:18080"
                }
                """);

        McpProperties properties = new McpProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        McpServerRegistryService service = new McpServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        McpServerRegistryService.RegisteredServer server = service.find("mock").orElseThrow();
        assertThat(server.readTimeoutMs()).isEqualTo(15000);
    }
}
