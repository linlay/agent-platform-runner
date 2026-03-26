package com.linlay.agentplatform.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryServiceTest {

    private final AgentMemoryService service = new AgentMemoryService();

    @TempDir
    Path tempDir;

    @Test
    void shouldIgnoreEmptyMemoryFiles() throws Exception {
        Path agentDir = tempDir.resolve("agent");
        Files.createDirectories(agentDir.resolve("memory"));
        Files.writeString(agentDir.resolve("memory").resolve("memory.md"), "");

        assertThat(service.loadMemory(agentDir)).isNull();
    }

    @Test
    void shouldAppendMemoryEntriesWithoutOverwritingExistingContent() throws Exception {
        Path agentDir = tempDir.resolve("agent");

        service.appendMemoryEntry(agentDir, "[2026-03-25T10:30:00Z] [mem_1]\nfirst");
        service.appendMemoryEntry(agentDir, "[2026-03-26T10:30:00Z] [mem_2]\nsecond");

        String content = Files.readString(agentDir.resolve("memory").resolve("memory.md"));
        assertThat(content).contains("[mem_1]").contains("[mem_2]").contains("\n\n");
    }
}
