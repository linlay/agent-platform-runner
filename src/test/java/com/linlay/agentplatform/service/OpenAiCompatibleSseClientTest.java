package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.AgentProviderProperties;
import com.linlay.agentplatform.config.LlmInteractionLogProperties;
import com.linlay.agentplatform.model.ModelProtocol;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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
