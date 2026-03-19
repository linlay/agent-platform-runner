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
}
