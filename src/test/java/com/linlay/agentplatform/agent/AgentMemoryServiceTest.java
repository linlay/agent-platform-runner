package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.MemoryStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveMemoryRootAndDbPath() {
        AgentMemoryService service = newService();

        assertThat(service.resolveMemoryRoot()).isEqualTo(tempDir.resolve("memory").toAbsolutePath().normalize());
        assertThat(service.resolveMemoryDbPath()).isEqualTo(tempDir.resolve("memory/memory.db").toAbsolutePath().normalize());
    }

    @Test
    void shouldAppendJournalEntriesIntoDatePartition() throws Exception {
        AgentMemoryService service = newService();

        service.appendJournalEntry(
                "mem_1",
                Instant.parse("2026-03-25T10:30:00Z"),
                "req-1",
                "chat-1",
                "agent-a",
                "chat:chat-1",
                "remembered one line summary",
                "remember",
                "general",
                6,
                List.of("alpha")
        );
        service.appendJournalEntry(
                "mem_2",
                Instant.parse("2026-03-25T11:30:00Z"),
                "req-2",
                "chat-2",
                "agent-b",
                "chat:chat-2",
                "another one line summary",
                "tool-write",
                "fact",
                5,
                List.of("beta")
        );

        Path journalFile = service.resolveJournalPath(LocalDate.of(2026, 3, 25));
        assertThat(Files.exists(journalFile)).isTrue();
        List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode first = objectMapper.readTree(lines.getFirst());
        JsonNode second = objectMapper.readTree(lines.get(1));
        assertThat(first.path("id").asText()).isEqualTo("mem_1");
        assertThat(first.path("summary").asText()).isEqualTo("remembered one line summary");
        assertThat(first.path("sourceType").asText()).isEqualTo("remember");
        assertThat(second.path("id").asText()).isEqualTo("mem_2");
        assertThat(second.path("agentKey").asText()).isEqualTo("agent-b");
        assertThat(service.relativeJournalPath(LocalDate.of(2026, 3, 25))).isEqualTo("journal/2026-03/2026-03-25.jsonl");
    }

    private AgentMemoryService newService() {
        MemoryStorageProperties properties = new MemoryStorageProperties();
        properties.setDir(tempDir.resolve("memory").toString());
        return new AgentMemoryService(properties, new ObjectMapper());
    }
}
