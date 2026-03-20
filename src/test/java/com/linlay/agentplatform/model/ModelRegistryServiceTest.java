package com.linlay.agentplatform.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.config.ProviderRegistryService;
import com.linlay.agentplatform.service.CatalogDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidModelWithPricingTiers() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("bailian-qwen3-max.yml"), """
                key: bailian-qwen3-max
                provider: bailian
                protocol: OPENAI
                modelId: qwen3-max
                isReasoner: true
                isFunction: true
                pricing:
                  promptPointsPer1k: 10
                  completionPointsPer1k: 30
                  perCallPoints: 1
                  priceRatio: 1.2
                  tiers:
                    - minInputTokens: 0
                      maxInputTokens: 8000
                      promptPointsPer1k: 8
                      completionPointsPer1k: 24
                      perCallPoints: 1
                      priceRatio: 1.0
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        ModelDefinition model = service.find("bailian-qwen3-max").orElseThrow();
        assertThat(model.provider()).isEqualTo("bailian");
        assertThat(model.protocol()).isEqualTo(ModelProtocol.OPENAI);
        assertThat(model.modelId()).isEqualTo("qwen3-max");
        assertThat(model.pricing().promptPointsPer1k()).isEqualTo(10);
        assertThat(model.pricing().completionPointsPer1k()).isEqualTo(30);
        assertThat(model.pricing().tiers()).hasSize(1);
        assertThat(model.pricing().tiers().getFirst().maxInputTokens()).isEqualTo(8000);
    }

    @Test
    void shouldRejectModelWhenProviderIsMissing() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("unknown-provider.yml"), """
                key: unknown-provider
                provider: missing
                protocol: OPENAI
                modelId: qwen3-max
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("unknown-provider")).isEmpty();
    }

    @Test
    void shouldKeepFirstOnDuplicateModelKey() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("a.yml"), """
                key: dup-key
                provider: bailian
                protocol: OPENAI
                modelId: first-model
                """);
        Files.writeString(modelsDir.resolve("b.yml"), """
                key: dup-key
                provider: bailian
                protocol: OPENAI
                modelId: second-model
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("dup-key").orElseThrow().modelId()).isEqualTo("first-model");
    }

    @Test
    void shouldRejectInvalidProtocol() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("bad-protocol.yml"), """
                key: bad-protocol
                provider: bailian
                protocol: FOO_PROTOCOL
                modelId: qwen3-max
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("bad-protocol")).isEmpty();
    }

    @Test
    void shouldRejectLegacyNewApiCompatibleProtocol() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("legacy-protocol.yml"), """
                key: legacy-protocol
                provider: bailian
                protocol: NEWAPI_OPENAI_COMPATIBLE
                modelId: qwen3-max
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("legacy-protocol")).isEmpty();
    }

    @Test
    void shouldRejectAnthropicProtocolUntilImplemented() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("anthropic.yml"), """
                key: anthropic-model
                provider: bailian
                protocol: ANTHROPIC
                modelId: claude-sonnet
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("anthropic-model")).isEmpty();
    }

    @Test
    void shouldReturnCatalogDiffWhenModelsChanged() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("a.yml"), """
                key: model-a
                provider: bailian
                protocol: OPENAI
                modelId: model-a
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        Files.writeString(modelsDir.resolve("b.yml"), """
                key: model-b
                provider: bailian
                protocol: OPENAI
                modelId: model-b
                """);

        CatalogDiff diff = service.refreshModels();
        assertThat(diff.addedKeys()).contains("model-b");
        assertThat(diff.changedKeys()).contains("model-b");
    }

    @Test
    void shouldDiscoverModelsFromNestedDirectories() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir.resolve("group-a"));
        Files.writeString(modelsDir.resolve("group-a/nested.yml"), """
                key: nested-model
                provider: bailian
                protocol: OPENAI
                modelId: nested-id
                """);

        ModelRegistryService service = new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        );

        assertThat(service.find("nested-model")).isPresent();
    }

    @Test
    void shouldFailFastOnLegacyJsonModelFile() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Files.writeString(modelsDir.resolve("legacy.json"), "{\"key\":\"legacy\"}");

        assertThatThrownBy(() -> new ModelRegistryService(
                new ObjectMapper(),
                modelProperties(modelsDir),
                providerRegistry(Map.of("bailian", "https://example.com"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy JSON model files are no longer supported");
    }

    private ModelProperties modelProperties(Path modelsDir) {
        ModelProperties properties = new ModelProperties();
        properties.setExternalDir(modelsDir.toString());
        return properties;
    }

    private ProviderRegistryService providerRegistry(Map<String, String> providers) throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        for (Map.Entry<String, String> entry : providers.entrySet()) {
            Files.writeString(providersDir.resolve(entry.getKey() + ".yml"), """
                    key: %s
                    baseUrl: %s
                    apiKey: dummy
                    defaultModel: dummy-model
                    """.formatted(entry.getKey(), entry.getValue()));
        }
        ProviderProperties properties = new ProviderProperties();
        properties.setExternalDir(providersDir.toString());
        return new ProviderRegistryService(properties);
    }

}
