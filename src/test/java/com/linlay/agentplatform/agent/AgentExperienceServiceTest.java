package com.linlay.agentplatform.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExperienceServiceTest {

    private final AgentExperienceService service = new AgentExperienceService();

    @TempDir
    Path tempDir;

    @Test
    void shouldIgnoreEmptyExperienceFiles() throws Exception {
        Path agentDir = tempDir.resolve("agent");
        Path experienceFile = agentDir.resolve("experiences").resolve("web-scraping").resolve("site-a-login.md");
        Files.createDirectories(experienceFile.getParent());
        Files.writeString(experienceFile, "");

        assertThat(service.loadExperiences(agentDir)).isEmpty();
        assertThat(service.formatForPrompt(service.loadExperiences(agentDir))).isEmpty();
    }
}
