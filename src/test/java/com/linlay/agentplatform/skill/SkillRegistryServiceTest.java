package com.linlay.agentplatform.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.linlay.agentplatform.util.CatalogDiff;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SkillRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadSkillFromDirectoryAndFrontmatter() throws Exception {
        Path skillFile = tempDir.resolve("screenshot").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: "Screen Skill"
                description: "capture screen"
                ---
                # Steps
                do this
                """);

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        SkillRegistryService service = new SkillRegistryService(properties);

        SkillDescriptor descriptor = service.find("screenshot").orElseThrow();
        assertThat(descriptor.id()).isEqualTo("screenshot");
        assertThat(descriptor.name()).isEqualTo("Screen Skill");
        assertThat(descriptor.description()).isEqualTo("capture screen");
        assertThat(descriptor.prompt()).contains("# Steps");
    }

    @Test
    void shouldTruncatePromptWhenConfiguredMaxExceeded() throws Exception {
        Path skillFile = tempDir.resolve("doc").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "name: doc\n".repeat(1000));

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        properties.setMaxPromptChars(32);
        SkillRegistryService service = new SkillRegistryService(properties);

        SkillDescriptor descriptor = service.find("doc").orElseThrow();
        assertThat(descriptor.promptTruncated()).isTrue();
        assertThat(descriptor.prompt()).contains("[TRUNCATED");
    }

    @Test
    void shouldWarnAndIgnoreInvalidRootFiles(CapturedOutput output) throws Exception {
        Files.writeString(tempDir.resolve("SKILL.md"), "invalid");
        Files.writeString(tempDir.resolve("script.py"), "print('invalid')");
        Path skillFile = tempDir.resolve("sample_alpha").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: "Math Basic"
                description: "basic math"
                ---
                run script
                """);

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        SkillRegistryService service = new SkillRegistryService(properties);

        assertThat(service.list()).hasSize(1);
        assertThat(service.find("sample_alpha")).isPresent();
        assertThat(output.getOut() + output.getErr())
                .contains("Invalid skill layout entry")
                .contains("skills/<skill-id>/SKILL.md");
    }

    @Test
    void shouldLoadMultipleSkillDirectories() throws Exception {
        writeSkill("sample_alpha", "Sample Alpha", "alpha operations");
        writeSkill("sample_beta", "Sample Beta", "beta operations");
        writeSkill("sample_gamma", "Sample Gamma", "gamma operations");

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        SkillRegistryService service = new SkillRegistryService(properties);

        assertThat(service.list())
                .extracting(SkillDescriptor::id)
                .containsExactlyInAnyOrder("sample_alpha", "sample_beta", "sample_gamma");
        assertThat(service.find("sample_alpha").orElseThrow().name()).isEqualTo("Sample Alpha");
        assertThat(service.find("sample_beta").orElseThrow().name()).isEqualTo("Sample Beta");
        assertThat(service.find("sample_gamma").orElseThrow().name()).isEqualTo("Sample Gamma");
    }

    @Test
    void shouldReturnCatalogDiffWhenSkillsChanged() throws Exception {
        writeSkill("sample_alpha", "Sample Alpha", "alpha operations");

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        SkillRegistryService service = new SkillRegistryService(properties);

        writeSkill("sample_beta", "Sample Beta", "beta operations");
        CatalogDiff diff = service.refreshSkills();

        assertThat(diff.addedKeys()).contains("sample_beta");
        assertThat(diff.changedKeys()).contains("sample_beta");
    }

    @Test
    void shouldIgnoreScaffoldSkillPlaceholder() throws Exception {
        Path skillFile = tempDir.resolve("custom_skill").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: "Custom Skill"
                description: "placeholder"
                scaffold: true
                ---
                """);

        SkillProperties properties = new SkillProperties();
        properties.setExternalDir(tempDir.toString());
        SkillRegistryService service = new SkillRegistryService(properties);

        assertThat(service.find("custom_skill")).isEmpty();
        assertThat(service.list()).isEmpty();
    }

    private void writeSkill(String id, String name, String description) throws Exception {
        Path skillFile = tempDir.resolve(id).resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: "%s"
                description: "%s"
                ---
                body
                """.formatted(name, description));
    }
}
