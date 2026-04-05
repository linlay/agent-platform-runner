package com.linlay.agentplatform.llm;

import com.linlay.agentplatform.config.ReasoningFormat;
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
    void shouldLoadOpenAiCompatFromProviderYaml() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("minimax.yml"), """
                key: minimax
                baseUrl: https://api.minimaxi.com/v1
                apiKey: dummy
                defaultModel: MiniMax-M2.7
                protocols:
                  OPENAI:
                    compat:
                      request:
                        whenReasoningEnabled:
                          reasoning_split: true
                      response:
                        reasoningFormats:
                          - REASONING_DETAILS_TEXT
                          - THINK_TAG_CONTENT
                        thinkTag:
                          start: "<think>"
                          end: "</think>"
                          stripFromContent: true
                """);

        ProviderRegistryService service = new ProviderRegistryService(providerProperties(providersDir));

        ProviderConfig config = service.find("minimax").orElseThrow();
        assertThat(config.getProtocol(com.linlay.agentplatform.model.ModelProtocol.OPENAI)).isNotNull();
        assertThat(config.getProtocol(com.linlay.agentplatform.model.ModelProtocol.OPENAI).compat()).isNotNull();
        assertThat(config.getProtocol(com.linlay.agentplatform.model.ModelProtocol.OPENAI).compat().request().whenReasoningEnabled())
                .containsEntry("reasoning_split", true);
        assertThat(config.getProtocol(com.linlay.agentplatform.model.ModelProtocol.OPENAI).compat().response().reasoningFormats())
                .containsExactly(ReasoningFormat.REASONING_DETAILS_TEXT, ReasoningFormat.THINK_TAG_CONTENT);
    }

    @Test
    void shouldRejectReservedCompatRequestKeysInProviderYaml() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("minimax.yml"), """
                key: minimax
                baseUrl: https://api.minimaxi.com/v1
                apiKey: dummy
                defaultModel: MiniMax-M2.7
                protocols:
                  OPENAI:
                    compat:
                      request:
                        whenReasoningEnabled:
                          response_format: {}
                """);

        assertThatThrownBy(() -> new ProviderRegistryService(providerProperties(providersDir)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved keys are not allowed")
                .hasMessageContaining("response_format");
    }

    @Test
    void shouldIgnoreLegacyModelFieldInProviderYaml() throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("bailian.yml"), """
                key: bailian
                baseUrl: https://dashscope.aliyuncs.com/compatible-mode
                apiKey: dummy
                model: qwen3.5-plus
                """);

        ProviderRegistryService service = new ProviderRegistryService(providerProperties(providersDir));

        assertThat(service.find("bailian")).isPresent();
        assertThat(service.find("bailian").orElseThrow().defaultModel()).isBlank();
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
