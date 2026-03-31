package com.linlay.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentMemoryService;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.embedding.EmbeddingService;
import com.linlay.agentplatform.service.llm.ProviderRegistryService;
import com.linlay.agentplatform.service.memory.AgentMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(prefix = "agent.memory.agent-memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentMemoryConfiguration {

    @Bean
    public EmbeddingService embeddingService(
            ProviderRegistryService providerRegistryService,
            AgentMemoryProperties agentMemoryProperties,
            WebClient.Builder loggingWebClientBuilder,
            ObjectMapper objectMapper
    ) {
        return new EmbeddingService(providerRegistryService, agentMemoryProperties, loggingWebClientBuilder, objectMapper);
    }

    @Bean
    public AgentMemoryStore agentMemoryStore(
            AgentMemoryProperties agentMemoryProperties,
            AgentMemoryService agentMemoryService,
            EmbeddingService embeddingService
    ) {
        return new AgentMemoryStore(agentMemoryProperties, agentMemoryService, embeddingService);
    }
}
