package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.AgentProviderProperties;
import com.linlay.agentplatform.config.LlmInteractionLogProperties;
import com.linlay.agentplatform.model.ModelProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleSseClientTest {

    @Test
    void shouldPreferConfiguredProtocolEndpointPath() {
        AgentProviderProperties properties = providerProperties("https://api.babelark.com", "/v1/chat/completions");
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                properties,
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );

        assertThat(client.resolveRawCompletionsUri("babelark", ModelProtocol.OPENAI))
                .isEqualTo("/v1/chat/completions");
    }

    @Test
    void shouldInferOpenAiEndpointWhenProviderDoesNotConfigureOne() {
        AgentProviderProperties versionedBaseUrl = providerProperties("https://example.com/v1", null);
        AgentProviderProperties rootBaseUrl = providerProperties("https://example.com", null);
        OpenAiCompatibleSseClient versionedClient = new OpenAiCompatibleSseClient(
                versionedBaseUrl,
                new ObjectMapper(),
                new LlmCallLogger(new LlmInteractionLogProperties()),
                null
        );
        OpenAiCompatibleSseClient rootClient = new OpenAiCompatibleSseClient(
                rootBaseUrl,
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
    void shouldAlwaysIncludeEnableThinkingFalseForNonBailianWhenReasoningDisabled() {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerProperties("https://api.babelark.com", "/v1/chat/completions"),
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
    void shouldAlwaysIncludeEnableThinkingTrueAndReasoningForNonBailianWhenReasoningEnabled() {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerProperties("https://api.babelark.com", "/v1/chat/completions"),
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
    void shouldSendEnableThinkingAlongsideRequiredToolChoiceForPlanGenerationPayload() {
        OpenAiCompatibleSseClient client = new OpenAiCompatibleSseClient(
                providerProperties("https://api.babelark.com", "/v1/chat/completions"),
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

    private AgentProviderProperties providerProperties(String baseUrl, String endpointPath) {
        AgentProviderProperties properties = new AgentProviderProperties();
        AgentProviderProperties.ProviderConfig provider = new AgentProviderProperties.ProviderConfig();
        provider.setBaseUrl(baseUrl);
        provider.setApiKey("dummy");
        if (endpointPath != null) {
            AgentProviderProperties.ProtocolConfig protocol = new AgentProviderProperties.ProtocolConfig();
            protocol.setEndpointPath(endpointPath);
            LinkedHashMap<com.linlay.agentplatform.model.ModelProtocol, AgentProviderProperties.ProtocolConfig> protocols = new LinkedHashMap<>();
            protocols.put(ModelProtocol.OPENAI, protocol);
            provider.setProtocols(protocols);
        }
        LinkedHashMap<String, AgentProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("babelark", provider);
        properties.setProviders(providers);
        return properties;
    }
}
