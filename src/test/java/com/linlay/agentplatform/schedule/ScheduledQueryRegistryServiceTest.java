package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledQueryRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidScheduleFile() throws Exception {
        Files.writeString(tempDir.resolve("daily.json"), """
                {
                  "name": "Daily Summary",
                  "enabled": true,
                  "cron": "0 0 9 * * *",
                  "agentKey": "demoModePlain",
                  "query": "ping",
                  "params": {
                    "k": "v"
                  }
                }
                """);

        ScheduleCatalogProperties properties = new ScheduleCatalogProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        ScheduledQueryDescriptor descriptor = service.find("daily").orElseThrow();
        assertThat(descriptor.id()).isEqualTo("daily");
        assertThat(descriptor.name()).isEqualTo("Daily Summary");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 0 9 * * *");
        assertThat(descriptor.agentKey()).isEqualTo("demoModePlain");
        assertThat(descriptor.teamId()).isNull();
        assertThat(descriptor.query()).isEqualTo("ping");
        assertThat(descriptor.params()).containsEntry("k", "v");
    }

    @Test
    void shouldSkipInvalidCronOrMissingTarget() throws Exception {
        Files.writeString(tempDir.resolve("invalid-cron.json"), """
                {
                  "cron": "invalid cron",
                  "agentKey": "demoModePlain",
                  "query": "ping"
                }
                """);
        Files.writeString(tempDir.resolve("missing-target.json"), """
                {
                  "cron": "0 0 9 * * *",
                  "query": "ping"
                }
                """);

        ScheduleCatalogProperties properties = new ScheduleCatalogProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        assertThat(service.list()).isEmpty();
    }
}
