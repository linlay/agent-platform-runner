package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.memory.AgentMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        String markdown = Files.readString(journalFile, StandardCharsets.UTF_8);
        String firstTs = Instant.parse("2026-03-25T10:30:00Z")
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String secondTs = Instant.parse("2026-03-25T11:30:00Z")
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertThat(markdown).contains("## " + firstTs);
        assertThat(markdown).contains("主题：chat:chat-1");
        assertThat(markdown).contains("chatId: chat-1");
        assertThat(markdown).contains("agentKey: agent-a");
        assertThat(markdown).contains("remembered one line summary");
        assertThat(markdown).contains("## " + secondTs);
        assertThat(markdown).contains("主题：chat:chat-2");
        assertThat(markdown).contains("chatId: chat-2");
        assertThat(markdown).contains("agentKey: agent-b");
        assertThat(markdown).contains("another one line summary");
        assertThat(markdown).doesNotContain("mem_1");
        assertThat(markdown).doesNotContain("requestId");
        assertThat(markdown).doesNotContain("标签：");
        assertThat(markdown).doesNotContain("sourceType");
        assertThat(markdown).doesNotContain("importance");
        assertThat(service.relativeJournalPath(LocalDate.of(2026, 3, 25))).isEqualTo("journal/2026-03/2026-03-25.md");
    }

    @Test
    void shouldOmitSubjectLineWhenSubjectKeyMissing() throws Exception {
        AgentMemoryService service = newService();

        service.appendJournalEntry(
                "mem_3",
                Instant.parse("2026-03-25T12:30:00Z"),
                "req-3",
                "chat-3",
                "agent-c",
                null,
                "summary without subject",
                "remember",
                "general",
                6,
                List.of("remember")
        );

        String markdown = Files.readString(service.resolveJournalPath(LocalDate.of(2026, 3, 25)), StandardCharsets.UTF_8);
        assertThat(markdown).contains("chatId: chat-3");
        assertThat(markdown).contains("agentKey: agent-c");
        assertThat(markdown).contains("summary without subject");
        assertThat(markdown).doesNotContain("主题：");
    }

    @Test
    void shouldSkipJournalWhenChatIdMissing() {
        AgentMemoryService service = newService();

        service.appendJournalEntry(
                "mem_4",
                Instant.parse("2026-03-25T13:30:00Z"),
                "req-4",
                null,
                "agent-d",
                "subject",
                "summary without chat",
                "tool-write",
                "general",
                5,
                List.of("tool")
        );

        assertThat(Files.exists(service.resolveJournalPath(LocalDate.of(2026, 3, 25)))).isFalse();
    }

    private AgentMemoryService newService() {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.getStorage().setDir(tempDir.resolve("memory").toString());
        return new AgentMemoryService(properties, new ObjectMapper());
    }
}
