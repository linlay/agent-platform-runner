package com.linlay.agentplatform.memory.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.ProviderProperties;
import com.linlay.agentplatform.llm.ProviderRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldCallOpenAiCompatibleEmbeddingsEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        EmbeddingService service = new EmbeddingService(
                providerRegistry("embedding-provider"),
                properties("embedding-provider", "text-embedding-3-small", 2, 1000),
                WebClient.builder().exchangeFunction(request -> {
                    requestBody.set(bodyOf(request));
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK)
                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                    .body("""
                                            {"data":[{"embedding":[0.25,0.75]},{"embedding":[0.5,0.5]}]}
                                            """)
                                    .build()
                    );
                }),
                objectMapper
        );

        List<Optional<float[]>> embeddings = service.embedBatch(List.of("alpha", "beta"));

        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0)).isPresent();
        assertThat(embeddings.get(0).orElseThrow()).containsExactly(0.25f, 0.75f);
        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("model").asText()).isEqualTo("text-embedding-3-small");
        assertThat(payload.path("encoding_format").asText()).isEqualTo("float");
        assertThat(payload.path("input")).hasSize(2);
    }

    @Test
    void shouldDegradeWhenProviderMissing() throws Exception {
        EmbeddingService service = new EmbeddingService(
                providerRegistry("other-provider"),
                properties("missing-provider", "text-embedding-3-small", 2, 1000),
                WebClient.builder(),
                objectMapper
        );

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.embed("alpha")).isEmpty();
    }

    @Test
    void shouldDegradeOnTimeout() throws Exception {
        EmbeddingService service = new EmbeddingService(
                providerRegistry("embedding-provider"),
                properties("embedding-provider", "text-embedding-3-small", 2, 10),
                WebClient.builder().exchangeFunction(request -> Mono.never()),
                objectMapper
        );

        assertThat(service.embed("alpha")).isEmpty();
    }

    @Test
    void shouldDegradeOnDimensionMismatch() throws Exception {
        EmbeddingService service = new EmbeddingService(
                providerRegistry("embedding-provider"),
                properties("embedding-provider", "text-embedding-3-small", 3, 1000),
                WebClient.builder().exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body("""
                                        {"data":[{"embedding":[0.25,0.75]}]}
                                        """)
                                .build()
                )),
                objectMapper
        );

        assertThat(service.embed("alpha")).isEmpty();
    }

    private ProviderRegistryService providerRegistry(String providerKey) throws Exception {
        Path providersDir = tempDir.resolve("providers");
        Files.createDirectories(providersDir);
        Files.writeString(providersDir.resolve(providerKey + ".yml"), """
                key: %s
                baseUrl: https://example.test
                apiKey: sk-test
                defaultModel: demo
                """.formatted(providerKey));
        ProviderProperties providerProperties = new ProviderProperties();
        providerProperties.setExternalDir(providersDir.toString());
        return new ProviderRegistryService(providerProperties);
    }

    private AgentMemoryProperties properties(String providerKey, String model, int dimension, int timeoutMs) {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setEmbeddingProviderKey(providerKey);
        properties.setEmbeddingModel(model);
        properties.setEmbeddingDimension(dimension);
        properties.setEmbeddingTimeoutMs(timeoutMs);
        return properties;
    }

    private String bodyOf(ClientRequest request) {
        RecordingOutputMessage outputMessage = new RecordingOutputMessage();
        request.body().insert(outputMessage, new org.springframework.web.reactive.function.BodyInserter.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return org.springframework.http.codec.ClientCodecConfigurer.create().getWriters();
            }

            @Override
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Map<String, Object> hints() {
                return java.util.Map.of();
            }
        }).block();
        return outputMessage.bodyAsString();
    }

    private static final class RecordingOutputMessage extends org.springframework.mock.http.client.reactive.MockClientHttpRequest {

        private RecordingOutputMessage() {
            super(org.springframework.http.HttpMethod.POST, "/v1/embeddings");
        }

        private String bodyAsString() {
            return getBodyAsString().block();
        }
    }
}
