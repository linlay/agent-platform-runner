package com.linlay.agentplatform.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AgentSkillSyncServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCopyOnlyDeclaredSkillsAndOverwriteExistingLocalCopy() throws Exception {
        Path skillsMarketDir = tempDir.resolve("skills-market");
        writeSkill(skillsMarketDir, "alpha", "market alpha");
        writeSkill(skillsMarketDir, "beta", "market beta");

        Path agentDir = tempDir.resolve("agents").resolve("demo");
        writeSkill(agentDir.resolve("skills"), "alpha", "local alpha");
        writeSkill(agentDir.resolve("skills"), "manual_extra", "manual extra");

        AgentSkillSyncService service = new AgentSkillSyncService(skillsMarketDir.toString());
        service.reconcileDeclaredSkills(agentDir, List.of("alpha"));

        assertThat(Files.readString(agentDir.resolve("skills/alpha/SKILL.md"))).contains("market alpha");
        assertThat(agentDir.resolve("skills/beta")).doesNotExist();
        assertThat(Files.readString(agentDir.resolve("skills/manual_extra/SKILL.md"))).contains("manual extra");
        assertThat(Files.readAllLines(agentDir.resolve("skills").resolve(AgentSkillSyncService.MANIFEST_FILE)))
                .containsExactly("alpha");
    }

    @Test
    void shouldRemovePreviouslyManagedSkillsThatAreNoLongerDeclared() throws Exception {
        Path skillsMarketDir = tempDir.resolve("skills-market");
        writeSkill(skillsMarketDir, "alpha", "market alpha");
        writeSkill(skillsMarketDir, "stale", "market stale");

        Path agentDir = tempDir.resolve("agents").resolve("demo");
        writeSkill(agentDir.resolve("skills"), "alpha", "old alpha");
        writeSkill(agentDir.resolve("skills"), "stale", "old stale");
        writeSkill(agentDir.resolve("skills"), "manual_extra", "manual extra");
        Files.write(
                agentDir.resolve("skills").resolve(AgentSkillSyncService.MANIFEST_FILE),
                List.of("alpha", "stale")
        );

        AgentSkillSyncService service = new AgentSkillSyncService(skillsMarketDir.toString());
        service.reconcileDeclaredSkills(agentDir, List.of("alpha"));

        assertThat(agentDir.resolve("skills/stale")).doesNotExist();
        assertThat(Files.readString(agentDir.resolve("skills/alpha/SKILL.md"))).contains("market alpha");
        assertThat(Files.readString(agentDir.resolve("skills/manual_extra/SKILL.md"))).contains("manual extra");
    }

    @Test
    void shouldRemoveManagedSkillsButKeepManualLocalSkillsWhenNothingIsDeclared() throws Exception {
        Path skillsMarketDir = tempDir.resolve("skills-market");
        writeSkill(skillsMarketDir, "alpha", "market alpha");

        Path agentDir = tempDir.resolve("agents").resolve("demo");
        writeSkill(agentDir.resolve("skills"), "alpha", "old alpha");
        writeSkill(agentDir.resolve("skills"), "manual_extra", "manual extra");
        Files.write(
                agentDir.resolve("skills").resolve(AgentSkillSyncService.MANIFEST_FILE),
                List.of("alpha")
        );

        AgentSkillSyncService service = new AgentSkillSyncService(skillsMarketDir.toString());
        service.reconcileDeclaredSkills(agentDir, List.of());

        assertThat(agentDir.resolve("skills/alpha")).doesNotExist();
        assertThat(Files.readString(agentDir.resolve("skills/manual_extra/SKILL.md"))).contains("manual extra");
        assertThat(agentDir.resolve("skills").resolve(AgentSkillSyncService.MANIFEST_FILE)).doesNotExist();
    }

    @Test
    void shouldWarnButNotFailWhenDeclaredSkillIsMissingFromMarket(CapturedOutput output) throws Exception {
        Path agentDir = tempDir.resolve("agents").resolve("demo");
        writeSkill(agentDir.resolve("skills"), "missing_skill", "local fallback");

        AgentSkillSyncService service = new AgentSkillSyncService(tempDir.resolve("skills-market").toString());
        service.reconcileDeclaredSkills(agentDir, List.of("missing_skill"));

        assertThat(Files.readString(agentDir.resolve("skills/missing_skill/SKILL.md"))).contains("local fallback");
        assertThat(output.getOut() + output.getErr()).contains("Skip syncing declared skill 'missing_skill'");
        assertThat(Files.readAllLines(agentDir.resolve("skills").resolve(AgentSkillSyncService.MANIFEST_FILE)))
                .containsExactly("missing_skill");
    }

    private void writeSkill(Path skillsRoot, String skillId, String body) throws Exception {
        Path skillDir = skillsRoot.resolve(skillId);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "%s"
                description: "demo"
                ---

                %s
                """.formatted(skillId, body));
    }
}
