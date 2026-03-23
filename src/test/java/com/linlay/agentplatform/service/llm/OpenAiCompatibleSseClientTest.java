package com.linlay.agentplatform.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.LlmInteractionLogProperties;
import com.linlay.agentplatform.config.ProviderProperties;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import com.linlay.agentplatform.model.ModelProtocol;
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
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerRegistry("https://api.babelark.com", "/v1/chat/completions"),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

        assertThat(client.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void shouldInferOpenAiEndpointWhenProviderDoesNotConfigureOne() throws Exception {
        OpenAiCompatibleSseClient versionedClient = new OpenAiCompatibleSseClient(
                providerRegistry("https://example.com/v1", null),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );
        OpenAiCompatibleSseClient rootClient = new OpenAiCompatibleSseClient(
                providerRegistry("https://example.com", null),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

        assertThat(versionedClient.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/chat/completions");
        assertThat(rootClient.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void shouldAlwaysIncludeEnableThinkingFalseForNonBailianWhenReasoningDisabled() throws Exception {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerRegistry("https://api.babelark.com", "/v1/chat/completions"),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

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
    void shouldAlwaysIncludeEnableThinkingTrueAndReasoningForNonBailianWhenReasoningEnabled() throws Exception {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerRegistry("https://api.babelark.com", "/v1/chat/completions"),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

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
                ComputePolicy.HIGH,
                true,
                4096
        );

        assertThat(request).containsEntry("enable_thinking", true);
        assertThat(request).containsEntry("reasoning", Map.of("effort", "high"));
    }

    @Test
    void shouldSendEnableThinkingAlongsideRequiredToolChoiceForPlanGenerationPayload() throws Exception {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerRegistry("https://api.babelark.com", "/v1/chat/completions"),
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

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

    private ProviderRegistryService providerRegistry(String baseUrl, String endpointPath) throws Exception {
        Path providersDir = tempDir.resolve(java.util.UUID.randomUUID().toString());
        Files.createDirectories(providersDir);
        String protocolsBlock = endpointPath == null
                ? ""
                : """
                protocols:
                  OPENAI:
                    endpointPath: %s
                """.formatted(endpointPath);
        Files.writeString(providersDir.resolve("babelark.yml"), """
                key: babelark
                baseUrl: %s
                apiKey: dummy
                %s
                """.formatted(baseUrl, protocolsBlock));
        ProviderProperties properties = new ProviderProperties();
        properties.setExternalDir(providersDir.toString());
        return new ProviderRegistryService(properties);
    }
}
