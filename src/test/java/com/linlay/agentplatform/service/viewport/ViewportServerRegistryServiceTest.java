package com.linlay.agentplatform.service.viewport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ViewportServerRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadServerFromRegistryFile() throws Exception {
        Path registryDir = tempDir.resolve("viewport-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: viewport-mock
                baseUrl: http://dynamic-host:11969
                endpointPath: /mcp
                headers:
                  x-viewport: "1"
                """);

        ViewportServerProperties properties = new ViewportServerProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        ViewportServerRegistryService service = new ViewportServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        ViewportServerRegistryService.RegisteredServer server = service.find("viewport-mock").orElseThrow();
        assertThat(server.baseUrl()).isEqualTo("http://dynamic-host:11969");
        assertThat(server.headers()).containsEntry("x-viewport", "1");
        assertThat(server.endpointUrl()).isEqualTo("http://dynamic-host:11969/mcp");
    }

    @Test
    void shouldBindTimeoutAndRetryFromRegistryFile() throws Exception {
        Path registryDir = tempDir.resolve("viewport-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: viewport-mock
                baseUrl: http://localhost:11969
                endpointPath: /mcp
                connectTimeoutMs: 2100
                readTimeoutMs: 19000
                retry: 4
                """);

        ViewportServerProperties properties = new ViewportServerProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        ViewportServerRegistryService service = new ViewportServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        ViewportServerRegistryService.RegisteredServer server = service.find("viewport-mock").orElseThrow();
        assertThat(server.connectTimeoutMs()).isEqualTo(2100);
        assertThat(server.readTimeoutMs()).isEqualTo(19000);
        assertThat(server.retry()).isEqualTo(4);
    }

    @Test
    void shouldIncreaseRegistryVersionAfterRefresh() throws Exception {
        Path registryDir = tempDir.resolve("viewport-servers");
        Files.createDirectories(registryDir);
        Files.writeString(registryDir.resolve("mock.yml"), """
                serverKey: viewport-mock
                baseUrl: http://localhost:11969
                endpointPath: /mcp
                """);

        ViewportServerProperties properties = new ViewportServerProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());
        ViewportServerRegistryService service = new ViewportServerRegistryService(new ObjectMapper(), properties);

        long firstVersion = service.currentVersion();
        service.refreshServers();
        long secondVersion = service.currentVersion();
        service.refreshServers();
        long thirdVersion = service.currentVersion();

        assertThat(secondVersion).isGreaterThan(firstVersion);
        assertThat(thirdVersion).isGreaterThan(secondVersion);
    }

    @Test
    void shouldLoadViewportServerFromNestedRegistryDirectory() throws Exception {
        Path registryDir = tempDir.resolve("viewport-servers");
        Files.createDirectories(registryDir.resolve("nested"));
        Files.writeString(registryDir.resolve("nested/mock.yml"), """
                serverKey: viewport-mock
                baseUrl: http://nested-host:11969
                endpointPath: /mcp
                """);

        ViewportServerProperties properties = new ViewportServerProperties();
        properties.getRegistry().setExternalDir(registryDir.toString());

        ViewportServerRegistryService service = new ViewportServerRegistryService(new ObjectMapper(), properties);
        service.refreshServers();

        assertThat(service.find("viewport-mock")).isPresent();
    }
}
