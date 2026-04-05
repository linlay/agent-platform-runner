package com.linlay.agentplatform.memory.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.ProviderConfig;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.llm.ProviderRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ProviderRegistryService providerRegistryService;
    private final AgentMemoryProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public EmbeddingService(
            ProviderRegistryService providerRegistryService,
            AgentMemoryProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.providerRegistryService = providerRegistryService;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Optional<float[]> embed(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        return embedBatch(List.of(text)).stream().findFirst().orElse(Optional.empty());
    }

    public List<Optional<float[]>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!isAvailable()) {
            return emptyResults(texts.size());
        }
        ProviderConfig provider = providerRegistryService.find(properties.getEmbeddingProviderKey()).orElse(null);
        if (provider == null || !StringUtils.hasText(provider.baseUrl())) {
            return emptyResults(texts.size());
        }

        try {
            JsonNode response = buildClient(provider)
                    .post()
                    .uri(uriBuilder -> uriBuilder.path("/v1/embeddings").build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(buildRequest(texts))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(Math.max(1, properties.getEmbeddingTimeoutMs())));
            return parseEmbeddings(response, texts.size());
        } catch (Exception ex) {
            log.debug("Embedding request failed, fallback to FTS-only search", ex);
            return emptyResults(texts.size());
        }
    }

    public boolean isAvailable() {
        if (!StringUtils.hasText(properties.getEmbeddingProviderKey()) || !StringUtils.hasText(properties.getEmbeddingModel())) {
            return false;
        }
        return providerRegistryService.find(properties.getEmbeddingProviderKey())
                .map(ProviderConfig::baseUrl)
                .filter(StringUtils::hasText)
                .isPresent();
    }

    private WebClient buildClient(ProviderConfig provider) {
        WebClient.Builder builder = webClientBuilder.clone().baseUrl(provider.baseUrl().trim());
        if (StringUtils.hasText(provider.apiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey().trim());
        }
        return builder.build();
    }

    private JsonNode buildRequest(List<String> texts) {
        return objectMapper.createObjectNode()
                .put("model", properties.getEmbeddingModel().trim())
                .put("encoding_format", "float")
                .set("input", objectMapper.valueToTree(texts));
    }

    private List<Optional<float[]>> parseEmbeddings(JsonNode response, int requestedSize) {
        if (response == null || !response.path("data").isArray()) {
            return emptyResults(requestedSize);
        }
        List<Optional<float[]>> results = new ArrayList<>(requestedSize);
        for (int index = 0; index < requestedSize; index++) {
            JsonNode item = response.path("data").path(index).path("embedding");
            if (!item.isArray()) {
                results.add(Optional.empty());
                continue;
            }
            float[] embedding = new float[item.size()];
            boolean valid = true;
            for (int i = 0; i < item.size(); i++) {
                JsonNode value = item.get(i);
                if (value == null || !value.isNumber()) {
                    valid = false;
                    break;
                }
                embedding[i] = value.floatValue();
            }
            if (!valid || embedding.length != properties.getEmbeddingDimension()) {
                results.add(Optional.empty());
                continue;
            }
            results.add(Optional.of(embedding));
        }
        while (results.size() < requestedSize) {
            results.add(Optional.empty());
        }
        return List.copyOf(results);
    }

    private List<Optional<float[]>> emptyResults(int size) {
        List<Optional<float[]>> results = new ArrayList<>(Math.max(0, size));
        for (int index = 0; index < size; index++) {
            results.add(Optional.empty());
        }
        return List.copyOf(results);
    }
}
