package com.linlay.agentplatform.service.llm;

import com.linlay.agentplatform.config.ProviderConfig;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadDefaultModelFromProviderYaml() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("bailian.yml"), """
                key: bailian
                baseUrl: https://dashscope.aliyuncs.com/compatible-mode
                apiKey: dummy
                defaultModel: qwen3.5-plus
                """);

        ProviderRegistryService service = new ProviderRegistryService(providerProperties(providersDir));

        ProviderConfig config = service.find("bailian").orElseThrow();
        assertThat(config.defaultModel()).isEqualTo("qwen3.5-plus");
    }

    @Test
    void shouldRejectLegacyModelFieldInProviderYaml() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("bailian.yml"), """
                key: bailian
                baseUrl: https://dashscope.aliyuncs.com/compatible-mode
                apiKey: dummy
                model: qwen3.5-plus
                """);

        assertThatThrownBy(() -> new ProviderRegistryService(providerProperties(providersDir)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy field 'model' is no longer supported")
                .hasMessageContaining("use 'defaultModel' instead");
    }

    @Test
    void shouldLoadProviderFromNestedDirectory() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir.resolve("group-a"));
        Files.writeString(providersDir.resolve("group-a/bailian.yml"), """
                key: bailian
                baseUrl: https://dashscope.aliyuncs.com/compatible-mode
                apiKey: dummy
                defaultModel: qwen3.5-plus
                """);

        ProviderRegistryService service = new ProviderRegistryService(providerProperties(providersDir));

        assertThat(service.find("bailian")).isPresent();
    }

    @Test
    void shouldIgnoreExampleProviderAndLoadDemoProvider() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("example-only.example.yml"), """
                key: example-only
                baseUrl: https://example-template.invalid
                apiKey: dummy
                defaultModel: template-model
                """);
        Files.writeString(providersDir.resolve("demo-live.demo.yml"), """
                key: demo-live
                baseUrl: https://demo-live.example
                apiKey: dummy
                defaultModel: demo-model
                """);

        ProviderRegistryService service = new ProviderRegistryService(providerProperties(providersDir));

        assertThat(service.find("example-only")).isEmpty();
        assertThat(service.find("demo-live")).isPresent();
    }

    private ProviderProperties providerProperties(Path providersDir) {
        ProviderProperties properties = new ProviderProperties();
        properties.setExternalDir(providersDir.toString());
        return properties;
    }
}
