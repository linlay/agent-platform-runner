package com.linlay.agentplatform.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.engine.policy.ComputePolicy;
import com.linlay.agentplatform.engine.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.LlmInteractionLogProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.config.properties.ModelProperties;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.model.ModelRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleSseClientTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreferConfiguredProtocolEndpointPath() throws Exception {
        OpenAiCompatibleSseClient client = client(providerYaml("https://api.babelark.com", "/v1/chat/completions", null));

        assertThat(client.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void shouldInferOpenAiEndpointWhenProviderDoesNotConfigureOne() throws Exception {
        OpenAiCompatibleSseClient versionedClient = client(providerYaml("https://example.com/v1", null, null));
        OpenAiCompatibleSseClient rootClient = client(providerYaml("https://example.com", null, null));

        assertThat(versionedClient.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/chat/completions");
        assertThat(rootClient.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void shouldAlwaysIncludeEnableThinkingFalseWhenReasoningDisabled() throws Exception {
        OpenAiCompatibleSseClient client = client(providerYaml("https://api.babelark.com", "/v1/chat/completions", null));

        Map<String, Object> request = client.buildRequestBody(
                "babelark",
                "Qwen3.5-397B-A17B",
                "system",
                List.of(),
                "user",
                List.of(),
                false,
                ToolChoice.AUTO,
                null,
                ComputePolicy.MEDIUM,
                false,
                4096
        );

        assertThat(request).containsEntry("enable_thinking", false);
        assertThat(request).doesNotContainKey("reasoning");
    }

    @Test
    void shouldApplyProviderCompatRequestWhenReasoningEnabled() throws Exception {
        String compat = """
                request:
                  whenReasoningEnabled:
                    reasoning_split: true
                """;
        OpenAiCompatibleSseClient client = client(providerYaml("https://api.minimaxi.com/v1", null, compat));

        Map<String, Object> request = client.buildRequestBody(
                "minimax",
                "MiniMax-M2.7",
                "system",
                List.of(),
                "user",
                List.of(),
                false,
                ToolChoice.AUTO,
                null,
                ComputePolicy.HIGH,
                true,
                4096
        );

        assertThat(request).containsEntry("enable_thinking", true);
        assertThat(request).containsEntry("reasoning", Map.of("effort", "high"));
        assertThat(request).containsEntry("reasoning_split", true);
    }

    @Test
    void shouldAllowModelCompatToOverrideProviderCompatRequestFields() throws Exception {
        String providerCompat = """
                request:
                  whenReasoningEnabled:
                    reasoning_split: true
                    gateway_mode: provider
                """;
        String modelCompat = """
                compat:
                  request:
                    whenReasoningEnabled:
                      reasoning_split: null
                      gateway_mode: model
                      model_only: true
                """;
        OpenAiCompatibleSseClient client = client(
                providerYaml("https://example.com/v1", null, providerCompat),
                modelYaml("babelark-minimax-m2_7", "babelark", "MiniMax-M2.7", modelCompat)
        );

        Map<String, Object> request = client.buildRequestBody(
                "babelark-minimax-m2_7",
                "babelark",
                "MiniMax-M2.7",
                "system",
                List.of(),
                "user",
                List.of(),
                false,
                ToolChoice.AUTO,
                null,
                ComputePolicy.MEDIUM,
                true,
                4096
        );

        assertThat(request).doesNotContainKey("reasoning_split");
        assertThat(request).containsEntry("gateway_mode", "model");
        assertThat(request).containsEntry("model_only", true);
    }

    @Test
    void shouldUseModelCompatThinkTagFormatsInSseParser() throws Exception {
        String modelCompat = """
                compat:
                  response:
                    reasoningFormats:
                      - REASONING_CONTENT
                      - THINK_TAG_CONTENT
                    thinkTag:
                      start: "<think>"
                      end: "</think>"
                      stripFromContent: true
                """;
        OpenAiCompatibleSseClient client = client(
                providerYaml("https://api.babelark.com", "/v1/chat/completions", null),
                modelYaml("babelark-minimax-m2_7", "babelark", "MiniMax-M2.7", modelCompat)
        );

        LlmDelta delta = client.buildSseDeltaParser("babelark", "babelark-minimax-m2_7", ModelProtocol.OPENAI)
                .parseOrNull("""
                        data: {"choices":[{"delta":{"content":"<think>思考</think>答案"}}]}
                        """);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isEqualTo("思考");
        assertThat(delta.content()).isEqualTo("答案");
    }

    @Test
    void shouldOmitReasoningSplitForMiniMaxM27WhenModelCompatDisablesIt() throws Exception {
        String providerCompat = """
                request:
                  whenReasoningEnabled:
                    reasoning_split: true
                """;
        String modelCompat = """
                compat:
                  request:
                    whenReasoningEnabled:
                      reasoning_split: null
                  response:
                    reasoningFormats:
                      - REASONING_CONTENT
                      - THINK_TAG_CONTENT
                    thinkTag:
                      start: "<think>"
                      end: "</think>"
                      stripFromContent: true
                """;
        OpenAiCompatibleSseClient client = client(
                providerYaml("https://api.minimaxi.com/v1", null, providerCompat),
                modelYaml("minimax-m2_7", "minimax", "MiniMax-M2.7", modelCompat)
        );

        Map<String, Object> request = client.buildRequestBody(
                "minimax-m2_7",
                "minimax",
                "MiniMax-M2.7",
                "system",
                List.of(),
                "user",
                List.of(),
                false,
                ToolChoice.AUTO,
                null,
                ComputePolicy.MEDIUM,
                true,
                4096
        );

        assertThat(request).containsEntry("enable_thinking", true);
        assertThat(request).containsEntry("reasoning", Map.of("effort", "medium"));
        assertThat(request).doesNotContainKey("reasoning_split");
    }

    @Test
    void shouldSendEnableThinkingAlongsideRequiredToolChoiceForPlanGenerationPayload() throws Exception {
        OpenAiCompatibleSseClient client = client(providerYaml("https://api.babelark.com", "/v1/chat/completions", null));

        List<LlmService.LlmFunctionTool> tools = List.of(new LlmService.LlmFunctionTool(
                "_plan_add_tasks_",
                "Create plan tasks",
                Map.of("type", "object"),
                false
        ));

        Map<String, Object> request = client.buildRequestBody(
                "babelark",
                "Qwen3.5-397B-A17B",
                "system",
                List.of(),
                "user",
                tools,
                false,
                ToolChoice.REQUIRED,
                null,
                ComputePolicy.MEDIUM,
                false,
                4096
        );

        assertThat(request).containsEntry("enable_thinking", false);
        assertThat(request).containsEntry("tool_choice", "required");
        assertThat(request).containsEntry("parallel_tool_calls", false);
        assertThat(request).containsKey("tools");
    }

    @Test
    void shouldFallbackToPermissiveObjectSchemaWhenJsonSchemaIsInvalid() throws Exception {
        OpenAiCompatibleSseClient client = client(providerYaml("https://api.babelark.com", "/v1/chat/completions", null));

        Map<String, Object> request = client.buildRequestBody(
                "babelark",
                "Qwen3.5-397B-A17B",
                "system",
                List.of(),
                "user",
                List.of(),
                false,
                ToolChoice.AUTO,
                "{invalid-json",
                ComputePolicy.MEDIUM,
                false,
                4096
        );

        assertThat(request).containsKey("response_format");
        assertThat(request.get("response_format")).isEqualTo(Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "response_schema",
                        "schema", Map.of("type", "object"),
                        "strict", false
                )
        ));
    }

    private OpenAiCompatibleSseClient client(String providerYaml, String... modelYamls) throws Exception {
        ProviderRegistryService providerRegistry = providerRegistry(providerYaml);
        ModelRegistryService modelRegistry = modelRegistry(providerRegistry, modelYamls);
        return new OpenAiCompatibleSseClient(
                providerRegistry,
                modelRegistry,
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );
    }

    private ProviderRegistryService providerRegistry(String providerYaml) throws Exception {
        Path providersDir = tempDir.resolve("providers-" + java.util.UUID.randomUUID());
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve("provider.yml"), providerYaml);
        ProviderProperties properties = new ProviderProperties();
        properties.setExternalDir(providersDir.toString());
        return new ProviderRegistryService(properties);
    }

    private ModelRegistryService modelRegistry(ProviderRegistryService providerRegistry, String... modelYamls) throws Exception {
        Path modelsDir = tempDir.resolve("models-" + java.util.UUID.randomUUID());
        Files.createDirectories(modelsDir);
        if (modelYamls != null) {
            for (int i = 0; i < modelYamls.length; i++) {
                Files.writeString(modelsDir.resolve("model-" + i + ".yml"), modelYamls[i]);
            }
        }
        ModelProperties properties = new ModelProperties();
        properties.setExternalDir(modelsDir.toString());
        return new ModelRegistryService(new ObjectMapper(), properties, providerRegistry);
    }

    private String providerYaml(String baseUrl, String endpointPath, String compatBlock) {
        String protocolsBlock = endpointPath == null && compatBlock == null
                ? ""
                : """
                protocols:
                  OPENAI:
                %s%s
                """.formatted(
                        endpointPath == null ? "" : "    endpointPath: %s%n".formatted(endpointPath),
                        compatBlock == null ? "" : "    compat:\n" + indent(compatBlock, 6)
                );
        return """
                key: %s
                baseUrl: %s
                apiKey: dummy
                defaultModel: default-model
                %s
                """.formatted(baseUrl.contains("minimaxi") ? "minimax" : "babelark", baseUrl, protocolsBlock);
    }

    private String modelYaml(String key, String provider, String modelId, String extraBlock) {
        return """
                key: %s
                provider: %s
                protocol: OPENAI
                modelId: %s
                isReasoner: true
                isFunction: true
                %s
                """.formatted(key, provider, modelId, extraBlock == null ? "" : extraBlock);
    }

    private String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return text.lines()
                .map(line -> line.isEmpty() ? line : prefix + line)
                .reduce("", (left, right) -> left + right + "\n");
    }
}
