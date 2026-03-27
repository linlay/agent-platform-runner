package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
    private ChatStorageProperties chatStorageProperties;

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
        Files.createDirectories(Path.of(chatStorageProperties.getDir()));
    }

    @Test
    void postUploadShouldStoreBinaryAndReturnCompactTicket() throws Exception {
        String chatId = UUID.randomUUID().toString();
        EntityExchangeResult<byte[]> uploadResult = upload(chatId, "req-upload-1", "notes.txt",
                MediaType.TEXT_PLAIN, "hello world".getBytes(StandardCharsets.UTF_8), null);

        JsonNode data = responseData(uploadResult);
        String responseChatId = data.path("chatId").asText();
        String resourceUrl = data.path("upload").path("url").asText();

        assertThat(responseChatId).isEqualTo(chatId);
        assertThat(data.path("upload").path("id").asText()).isEqualTo("r01");
        assertThat(data.path("upload").path("type").asText()).isEqualTo("file");
        assertThat(data.path("upload").path("name").asText()).isEqualTo("notes.txt");
        assertThat(data.path("upload").path("mimeType").asText()).isEqualTo("text/plain");
        assertThat(data.path("upload").path("sizeBytes").asLong()).isEqualTo(11L);
        assertThat(data.path("upload").path("sha256").asText()).isEqualTo(sha256Hex("hello world".getBytes(StandardCharsets.UTF_8)));
        assertThat(data.path("reference").isMissingNode()).isTrue();
        assertThat(data.path("upload").path("uploadUrl").isMissingNode()).isTrue();
        assertThat(data.path("upload").path("method").isMissingNode()).isTrue();
        assertThat(resourceUrl).isEqualTo("/api/resource?file=" + chatId + "%2Fnotes.txt");
        assertThat(resourceUrl).doesNotContain("uploads");

        webTestClient.get()
                .uri(URI.create(resourceUrl))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, "text/plain")
                .expectBody(byte[].class)
                .value(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello world"));

        Path assetPath = Path.of(chatStorageProperties.getDir()).resolve(chatId).resolve("notes.txt");
        assertThat(Files.readString(assetPath, StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    void postUploadShouldGenerateChatIdWhenMissing() throws Exception {
        EntityExchangeResult<byte[]> uploadResult = upload(null, "req-upload-no-chat", "notes.txt",
                MediaType.TEXT_PLAIN, "hello world".getBytes(StandardCharsets.UTF_8), null);

        JsonNode data = responseData(uploadResult);
        String chatId = data.path("chatId").asText();

        assertThat(UUID.fromString(chatId)).isNotNull();
        assertThat(data.path("upload").path("id").asText()).isEqualTo("r01");

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/chat").queryParam("chatId", chatId).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.chatId").isEqualTo(chatId)
                .jsonPath("$.data.chatName").isEqualTo("新对话");
    }

    @Test
    void postUploadShouldReuseGeneratedChatIdAcrossFollowupUpload() throws Exception {
        JsonNode firstData = responseData(upload(null, "req-upload-generated-1", "first.png",
                MediaType.IMAGE_PNG, new byte[]{1, 2, 3}, null));
        String chatId = firstData.path("chatId").asText();

        JsonNode secondData = responseData(upload(chatId, "req-upload-generated-2", "second.png",
                MediaType.IMAGE_PNG, new byte[]{4, 5, 6}, null));

        assertThat(secondData.path("chatId").asText()).isEqualTo(chatId);
        assertThat(firstData.path("upload").path("id").asText()).isEqualTo("r01");
        assertThat(secondData.path("upload").path("id").asText()).isEqualTo("r02");

        Path chatDir = Path.of(chatStorageProperties.getDir()).resolve(chatId);
        assertThat(Files.exists(chatDir.resolve("first.png"))).isTrue();
        assertThat(Files.exists(chatDir.resolve("second.png"))).isTrue();
    }

    @Test
    void postUploadShouldKeepOriginalFilenameAndAppendSuffixForConflicts() throws Exception {
        String chatId = UUID.randomUUID().toString();

        JsonNode firstData = responseData(upload(chatId, "req-upload-name-1", "t.png",
                MediaType.IMAGE_PNG, new byte[]{1, 2, 3}, null));
        JsonNode secondData = responseData(upload(chatId, "req-upload-name-2", "t.png",
                MediaType.IMAGE_PNG, new byte[]{4, 5, 6}, null));

        assertThat(firstData.path("upload").path("url").asText())
                .isEqualTo("/api/resource?file=" + chatId + "%2Ft.png");
        assertThat(secondData.path("upload").path("url").asText())
                .isEqualTo("/api/resource?file=" + chatId + "%2Ft%20%282%29.png");

        Path chatDir = Path.of(chatStorageProperties.getDir()).resolve(chatId);
        assertThat(Files.exists(chatDir.resolve("t.png"))).isTrue();
        assertThat(Files.exists(chatDir.resolve("t (2).png"))).isTrue();
    }

    @Test
    void postUploadShouldBeIdempotentForSameChatAndRequestId() throws Exception {
        String chatId = UUID.randomUUID().toString();
        byte[] payload = new byte[]{1, 2, 3};
        EntityExchangeResult<byte[]> first = upload(chatId, "req-upload-2", "photo.png", MediaType.IMAGE_PNG, payload, null);
        EntityExchangeResult<byte[]> second = upload(chatId, "req-upload-2", "photo.png", MediaType.IMAGE_PNG, payload, null);

        JsonNode firstData = responseData(first);
        JsonNode secondData = responseData(second);
        assertThat(secondData).isEqualTo(firstData);
    }

    @Test
    void postUploadShouldRejectDifferentPayloadForSameRequestId() throws Exception {
        String chatId = UUID.randomUUID().toString();
        upload(chatId, "req-upload-3", "draft.txt", MediaType.TEXT_PLAIN, "hello".getBytes(StandardCharsets.UTF_8), null);

        webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(chatId, "req-upload-3", "changed.txt",
                        MediaType.TEXT_PLAIN, "world".getBytes(StandardCharsets.UTF_8), null)))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void postUploadShouldRejectShaMismatchWithoutLeavingFile() throws Exception {
        String chatId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(chatId, "req-upload-5", "checksum.txt",
                        MediaType.TEXT_PLAIN, "hello".getBytes(StandardCharsets.UTF_8),
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
                .exchange()
                .expectStatus().isBadRequest();

        Path assetPath = Path.of(chatStorageProperties.getDir()).resolve(chatId).resolve("checksum.txt");
        assertThat(Files.exists(assetPath)).isFalse();
    }

    @Test
    void postUploadShouldStartAtR01WhenLegacyReferenceIdsExist() throws Exception {
        String chatId = UUID.randomUUID().toString();
        Path chatDir = Path.of(chatStorageProperties.getDir()).resolve(chatId);
        Files.createDirectories(chatDir.resolve("uploads"));
        Files.writeString(chatDir.resolve("uploads").resolve("legacy.txt"), "legacy");
        Files.createDirectories(chatDir.resolve(".uploads"));
        Files.writeString(chatDir.resolve(".uploads").resolve("f1.json"), """
                {"requestId":"legacy-1","chatId":"%s","referenceId":"f1","type":"file","name":"legacy.txt","sizeBytes":6,"mimeType":"text/plain","sha256":null,"relativePath":"uploads/legacy.txt","status":"completed","createdAt":1,"completedAt":2}
                """.formatted(chatId));
        Files.writeString(chatDir.resolve(".uploads").resolve("i1.json"), """
                {"requestId":"legacy-2","chatId":"%s","referenceId":"i1","type":"image","name":"legacy.png","sizeBytes":3,"mimeType":"image/png","sha256":null,"relativePath":"uploads/legacy.png","status":"completed","createdAt":1,"completedAt":2}
                """.formatted(chatId));

        JsonNode data = responseData(upload(chatId, "req-upload-r-prefix", "fresh.txt",
                MediaType.TEXT_PLAIN, "fresh".getBytes(StandardCharsets.UTF_8), null));

        assertThat(data.path("upload").path("id").asText()).isEqualTo("r01");
    }

    private EntityExchangeResult<byte[]> upload(
            String chatId,
            String requestId,
            String filename,
            MediaType mediaType,
            byte[] bytes,
            String sha256
    ) {
        return webTestClient.post()
                .uri("/api/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart(chatId, requestId, filename, mediaType, bytes, sha256)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .returnResult();
    }

    private org.springframework.util.MultiValueMap<String, HttpEntity<?>> multipart(
            String chatId,
            String requestId,
            String filename,
            MediaType mediaType,
            byte[] bytes,
            String sha256
    ) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("requestId", requestId);
        if (chatId != null) {
            builder.part("chatId", chatId);
        }
        if (sha256 != null) {
            builder.part("sha256", sha256);
        }
        builder.part("file", new NamedByteArrayResource(filename, bytes))
                .contentType(mediaType);
        return builder.build();
    }

    private JsonNode responseData(EntityExchangeResult<byte[]> result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponseBodyContent());
        return root.path("data");
    }

    private String sha256Hex(byte[] payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(String filename, byte[] bytes) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
