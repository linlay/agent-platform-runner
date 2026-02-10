package com.linlay.springaiagw.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
public class SpringAiConfiguration {

    @Bean("bailianChatModel")
    public ChatModel bailianChatModel(AgentProviderProperties properties) {
        return buildChatModel("bailian", properties.getBailian());
    }

    @Bean("siliconflowChatModel")
    public ChatModel siliconflowChatModel(AgentProviderProperties properties) {
        return buildChatModel("siliconflow", properties.getSiliconflow());
    }

    @Bean("bailianChatClient")
    public ChatClient bailianChatClient(@Qualifier("bailianChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean("siliconflowChatClient")
    public ChatClient siliconflowChatClient(@Qualifier("siliconflowChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    private ChatModel buildChatModel(String providerName, AgentProviderProperties.ProviderConfig providerConfig) {
        assertProviderConfig(providerName, providerConfig);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(providerConfig.getBaseUrl())
                .apiKey(providerConfig.getApiKey())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(providerConfig.getModel())
                .temperature(0.2)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private void assertProviderConfig(String providerName, AgentProviderProperties.ProviderConfig providerConfig) {
        Assert.notNull(providerConfig, "Missing config: agent.providers." + providerName);
        Assert.hasText(providerConfig.getBaseUrl(),
                "Missing config: agent.providers." + providerName + ".base-url");
        Assert.hasText(providerConfig.getApiKey(),
                "Missing config: agent.providers." + providerName + ".api-key");
        Assert.hasText(providerConfig.getModel(),
                "Missing config: agent.providers." + providerName + ".model");
    }
}
