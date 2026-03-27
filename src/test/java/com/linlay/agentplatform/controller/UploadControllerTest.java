package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.service.llm.LlmCallSpec;
import com.linlay.agentplatform.service.llm.LlmService;
import com.linlay.agentplatform.testsupport.StubLlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "agent.providers.bailian.base-url=https://example.com/v1",
                "agent.providers.bailian.api-key=test-bailian-key",
                "agent.providers.bailian.default-model=test-bailian-model",
                "agent.providers.siliconflow.base-url=https://example.com/v1",
                "agent.providers.siliconflow.api-key=test-siliconflow-key",
                "agent.providers.siliconflow.default-model=test-siliconflow-model",
                "agent.auth.enabled=false",
                "chat.storage.dir=${java.io.tmpdir}/agent-platform-runner-upload-chats-${random.uuid}",
                "chat.storage.index.sqlite-file=${java.io.tmpdir}/agent-platform-runner-upload-chats-db-${random.uuid}/chats.db",
                "agent.data.external-dir=${java.io.tmpdir}/agent-platform-runner-upload-assets-${random.uuid}",
                "agent.skills.external-dir=${java.io.tmpdir}/agent-platform-runner-upload-skills-${random.uuid}",
                "agent.schedule.external-dir=${java.io.tmpdir}/agent-platform-runner-upload-schedules-${random.uuid}"
        }
)
@AutoConfigureWebTestClient
@Import(UploadControllerTest.TestLlmServiceConfig.class)
class UploadControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DataProperties dataProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestLlmServiceConfig {
        @Bean
        @Primary
        LlmService llmService() {
            return new StubLlmService() {
                @Override
                protected Flux<String> contentBySpec(LlmCallSpec spec) {
                    return Flux.just("test");
                }

                @Override
                public Mono<String> completeText(String providerKey, String model, String systemPrompt, String userPrompt) {
                    return Mono.just("{}");
                }

                @Override
                public Mono<String> completeText(String providerKey, String model, String systemPrompt, String userPrompt, String stage) {
                    return completeText(providerKey, model, systemPrompt, userPrompt);
                }
            };
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(Path.of(dataProperties.getExternalDir()));
    }

    @Test
    void postUploadShouldCreateReservationAndPersistBinary() throws Exception {
        EntityExchangeResult<byte[]> reserveResult = webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "requestId": "req-upload-1",
                          "type": "file",
                          "name": "notes.txt",
                          "sizeBytes": 11,
                          "mimeType": "text/plain"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.requestId").isEqualTo("req-upload-1")
                .jsonPath("$.data.reference.id").isEqualTo("f1")
                .jsonPath("$.data.upload.method").isEqualTo("PUT")
                .returnResult();

        JsonNode data = responseData(reserveResult);
        String chatId = data.path("chatId").asText();
        String uploadUrl = data.path("upload").path("url").asText();
        String resourceUrl = data.path("reference").path("url").asText();

        assertThat(UUID.fromString(chatId)).isNotNull();
        assertThat(uploadUrl).isEqualTo("/api/upload/" + chatId + "/f1");
        assertThat(resourceUrl).isEqualTo("/api/resource?file=" + chatId + "%2Fuploads%2Ff1-notes.txt");

        webTestClient.put()
                .uri(uploadUrl)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue("hello world".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get()
                .uri(URI.create(resourceUrl))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, "text/plain")
                .expectBody(byte[].class)
                .value(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello world"));
    }

    @Test
    void postUploadShouldBeIdempotentForSameChatAndRequestId() throws Exception {
        String chatId = UUID.randomUUID().toString();
        EntityExchangeResult<byte[]> first = reserveUpload(chatId, "req-upload-2", "image", "photo.png", 3, "image/png", null);
        EntityExchangeResult<byte[]> second = reserveUpload(chatId, "req-upload-2", "image", "photo.png", 3, "image/png", null);

        JsonNode firstData = responseData(first);
        JsonNode secondData = responseData(second);
        assertThat(secondData).isEqualTo(firstData);
    }

    @Test
    void postUploadShouldRejectDifferentPayloadForSameRequestId() {
        String chatId = UUID.randomUUID().toString();
        reserveUpload(chatId, "req-upload-3", "file", "draft.txt", 5, "text/plain", null);

        webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "requestId": "req-upload-3",
                          "chatId": "%s",
                          "type": "file",
                          "name": "changed.txt",
                          "sizeBytes": 5,
                          "mimeType": "text/plain"
                        }
                        """.formatted(chatId))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void putUploadShouldRejectSizeMismatchWithoutLeavingFile() throws Exception {
        String chatId = UUID.randomUUID().toString();
        EntityExchangeResult<byte[]> reserveResult = reserveUpload(chatId, "req-upload-4", "file", "greeting.txt", 5, "text/plain", null);
        String uploadUrl = responseData(reserveResult).path("upload").path("url").asText();

        webTestClient.put()
                .uri(uploadUrl)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue("too long".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isBadRequest();

        Path assetPath = Path.of(dataProperties.getExternalDir()).resolve(chatId).resolve("uploads").resolve("f1-greeting.txt");
        assertThat(Files.exists(assetPath)).isFalse();
    }

    @Test
    void putUploadShouldRejectShaMismatchWithoutLeavingFile() throws Exception {
        String chatId = UUID.randomUUID().toString();
        EntityExchangeResult<byte[]> reserveResult = reserveUpload(
                chatId,
                "req-upload-5",
                "file",
                "checksum.txt",
                5,
                "text/plain",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        String uploadUrl = responseData(reserveResult).path("upload").path("url").asText();

        webTestClient.put()
                .uri(uploadUrl)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue("hello".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isBadRequest();

        Path assetPath = Path.of(dataProperties.getExternalDir()).resolve(chatId).resolve("uploads").resolve("f1-checksum.txt");
        assertThat(Files.exists(assetPath)).isFalse();
    }

    private EntityExchangeResult<byte[]> reserveUpload(
            String chatId,
            String requestId,
            String type,
            String name,
            long sizeBytes,
            String mimeType,
            String sha256
    ) {
        String json = """
                {
                  "requestId": "%s",
                  "chatId": "%s",
                  "type": "%s",
                  "name": "%s",
                  "sizeBytes": %d,
                  "mimeType": "%s"%s
                }
                """.formatted(
                requestId,
                chatId,
                type,
                name,
                sizeBytes,
                mimeType,
                sha256 == null ? "" : ",\n  \"sha256\": \"" + sha256 + "\""
        );
        return webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .returnResult();
    }

    private JsonNode responseData(EntityExchangeResult<byte[]> result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponseBodyContent());
        return root.path("data");
    }
}
