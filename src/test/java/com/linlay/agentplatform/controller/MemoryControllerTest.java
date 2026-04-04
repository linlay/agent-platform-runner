package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentProperties;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "agent.providers.bailian.base-url=https://example.com/v1",
                "agent.providers.bailian.api-key=test-bailian-key",
                "agent.providers.bailian.default-model=test-bailian-model",
                "agent.providers.babelark.base-url=https://example.com/v1",
                "agent.providers.babelark.api-key=test-babelark-key",
                "agent.providers.babelark.default-model=test-babelark-model",
                "agent.providers.siliconflow.base-url=https://example.com/v1",
                "agent.providers.siliconflow.api-key=test-siliconflow-key",
                "agent.providers.siliconflow.default-model=test-siliconflow-model",
                "agent.auth.enabled=false",
                "chat.storage.dir=${java.io.tmpdir}/agent-platform-runner-memory-chats-${random.uuid}",
                "chat.storage.index.sqlite-file=${java.io.tmpdir}/agent-platform-runner-memory-db-${random.uuid}/chats.db",
                "agent.skills.external-dir=${java.io.tmpdir}/agent-platform-runner-memory-skills-${random.uuid}",
                "agent.schedule.external-dir=${java.io.tmpdir}/agent-platform-runner-memory-schedules-${random.uuid}",
                "agent.root.external-dir=${java.io.tmpdir}/agent-platform-runner-memory-root-${random.uuid}",
                "agent.memory.storage.dir=${java.io.tmpdir}/agent-platform-runner-memory-store-${random.uuid}",
                "agent.memory.remember.model-key=remember-test-model",
                "agent.memory.remember.timeout-ms=100"
        }
)
@AutoConfigureWebTestClient
@Import(MemoryControllerTest.TestLlmServiceConfig.class)
class MemoryControllerTest {

    private static final Path TEST_PROVIDERS_DIR = prepareProvidersDir();
    private static final Path TEST_MODELS_DIR = prepareModelsDir();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ChatStorageProperties chatStorageProperties;

    @Autowired
    private AgentMemoryProperties memoryStorageProperties;

    @Autowired
    private AgentProperties agentProperties;

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("agent.providers.external-dir", () -> TEST_PROVIDERS_DIR.toString());
        registry.add("agent.models.external-dir", () -> TEST_MODELS_DIR.toString());
    }

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
                    assertThat(providerKey).isEqualTo("babelark");
                    assertThat(model).isEqualTo("remember-model-id");
                    if (userPrompt != null && userPrompt.contains("超时回复")) {
                        return Mono.never();
                    }
                    if (userPrompt != null && userPrompt.contains("新回复")) {
                        return Mono.just("{\"items\":[{\"summary\":\"用户确认新回复应保留为记忆\"}]}");
                    }
                    if (userPrompt != null && userPrompt.contains("第一次回复")) {
                        return Mono.just("{\"items\":[{\"summary\":\"用户首次对话的关键回复值得保留\",\"subjectKey\":\"chat-memory-demo\"}]}");
                    }
                    if (userPrompt != null && userPrompt.contains("旧回复")) {
                        return Mono.just("{\"items\":[{\"summary\":\"旧回复也被提炼成一条记忆\"}]}");
                    }
                    return Mono.just("{\"items\":[]}");
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
        Path memoryRoot = Path.of(memoryStorageProperties.getStorage().getDir());
        if (Files.exists(memoryRoot)) {
            try (var stream = Files.walk(memoryRoot)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .filter(path -> !path.equals(memoryRoot))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        });
            }
        }
        Files.createDirectories(memoryRoot);
        Files.createDirectories(Path.of(agentProperties.getExternalDir()));
    }

    @Test
    void rememberShouldCaptureChatHistoryIntoGlobalMemory() throws Exception {
        String chatId = UUID.randomUUID().toString();
        seedChat(chatId, "run-remember-1", "你好", "这是第一次回复", List.of(reference("r01")));

        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_001",
                        "chatId", chatId
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.accepted").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("captured")
                .jsonPath("$.data.runId").doesNotExist()
                .jsonPath("$.data.memoryPath").isEqualTo(todayJournalRelativePath())
                .jsonPath("$.data.memoryRoot").isEqualTo(memoryRoot().toString())
                .jsonPath("$.data.memoryCount").isEqualTo(1)
                .jsonPath("$.data.items[0].summary").isEqualTo("用户首次对话的关键回复值得保留")
                .jsonPath("$.data.items[0].subjectKey").isEqualTo("chat-memory-demo")
                .jsonPath("$.data.stored[0].requestId").isEqualTo("remember_req_001")
                .jsonPath("$.data.stored[0].chatId").isEqualTo(chatId)
                .jsonPath("$.data.stored[0].agentKey").isEqualTo("_unknown")
                .jsonPath("$.data.stored[0].summary").isEqualTo("用户首次对话的关键回复值得保留")
                .jsonPath("$.data.promptPreview.chatName").exists()
                .jsonPath("$.data.promptPreview.rawMessageCount").isEqualTo(2)
                .jsonPath("$.data.promptPreview.referenceCount").isEqualTo(1)
                .jsonPath("$.data.promptPreview.userPrompt").value(value -> {
                    assertThat((String) value).contains("chat preview");
                    assertThat((String) value).doesNotContain("\"rawMessages\"");
                })
                .jsonPath("$.data.detail").value(value -> assertThat((String) value).contains(memoryRoot().toString()));

        Path journalFile = memoryRoot().resolve(todayJournalRelativePath());
        assertThat(Files.exists(journalFile)).isTrue();
        String markdown = Files.readString(journalFile, StandardCharsets.UTF_8);
        assertThat(markdown).contains("## ");
        assertThat(markdown).contains("主题：chat-memory-demo");
        assertThat(markdown).contains("chatId: " + chatId);
        assertThat(markdown).contains("agentKey: _unknown");
        assertThat(markdown).contains("用户首次对话的关键回复值得保留");
        assertThat(markdown).doesNotContain("requestId");
        assertThat(markdown).doesNotContain("标签：remember");
        assertThat(markdown).doesNotContain("sourceType");
        assertThat(markdown).doesNotContain("importance");
        assertThat(Files.exists(memoryRoot().resolve("memory.db"))).isTrue();
    }

    @Test
    void rememberShouldAppendJournalEntriesAcrossRepeatedRequests() throws Exception {
        String chatId = UUID.randomUUID().toString();
        seedChat(chatId, "run-remember-2", "你好", "旧回复", List.of());

        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_same",
                        "chatId", chatId
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.memoryCount").isEqualTo(1)
                .jsonPath("$.data.stored[0].summary").isEqualTo("旧回复也被提炼成一条记忆");

        Path journalFile = memoryRoot().resolve(todayJournalRelativePath());
        String firstSnapshot = Files.readString(journalFile, StandardCharsets.UTF_8);
        assertThat(firstSnapshot).contains("旧回复也被提炼成一条记忆");
        assertThat(firstSnapshot.split("(?m)^## ").length - 1).isEqualTo(1);

        seedChat(chatId, "run-remember-2", "你好", "新回复", List.of());

        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_same",
                        "chatId", chatId
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.memoryCount").isEqualTo(1)
                .jsonPath("$.data.stored[0].summary").isEqualTo("用户确认新回复应保留为记忆");

        String secondSnapshot = Files.readString(journalFile, StandardCharsets.UTF_8);
        assertThat(secondSnapshot).contains("旧回复也被提炼成一条记忆");
        assertThat(secondSnapshot).contains("用户确认新回复应保留为记忆");
        assertThat(secondSnapshot.split("(?m)^## ").length - 1).isEqualTo(2);
    }

    @Test
    void rememberShouldReturn500WhenExtractionTimesOut() throws Exception {
        String chatId = UUID.randomUUID().toString();
        seedChat(chatId, "run-remember-timeout", "你好", "这是超时回复", List.of());

        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_timeout",
                        "chatId", chatId
                ))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(500)
                .jsonPath("$.msg").value(value -> assertThat((String) value).contains("timed out"));

        Path journalFile = memoryRoot().resolve(todayJournalRelativePath());
        assertThat(Files.exists(journalFile)).isFalse();
    }

    @Test
    void rememberShouldAcceptNonUuidChatId() {
        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_non_uuid_chat",
                        "chatId", "not-a-uuid"
                ))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rememberShouldReturn404WhenChatDoesNotExist() {
        webTestClient.post()
                .uri("/api/remember")
                .bodyValue(Map.of(
                        "requestId", "remember_req_missing_chat",
                        "chatId", UUID.randomUUID().toString()
                ))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }

    @Test
    void learnShouldReturnNotConnectedWithoutSubjectKey() {
        String chatId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/learn")
                .bodyValue(Map.of(
                        "requestId", "learn_req_001",
                        "chatId", chatId
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.accepted").isEqualTo(false)
                .jsonPath("$.data.status").isEqualTo("not_connected")
                .jsonPath("$.data.runId").doesNotExist()
                .jsonPath("$.data.subjectKey").doesNotExist();
    }

    @Test
    void learnShouldEchoSubjectKeyWhenProvided() {
        String chatId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/learn")
                .bodyValue(Map.of(
                        "requestId", "learn_req_002",
                        "chatId", chatId,
                        "subjectKey", "user:alice"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.accepted").isEqualTo(false)
                .jsonPath("$.data.status").isEqualTo("not_connected")
                .jsonPath("$.data.runId").doesNotExist()
                .jsonPath("$.data.subjectKey").isEqualTo("user:alice");
    }

    private void seedChat(
            String chatId,
            String runId,
            String userMessage,
            String assistantReply,
            List<Map<String, Object>> references
    ) throws Exception {
        Path historyFile = Path.of(chatStorageProperties.getDir()).resolve(chatId + ".jsonl");
        long now = System.currentTimeMillis();

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("requestId", runId);
        query.put("chatId", chatId);
        query.put("agentKey", "dailyOfficeAssistant");
        query.put("role", "user");
        query.put("message", userMessage);
        if (references != null && !references.isEmpty()) {
            query.put("references", references);
        }

        Map<String, Object> queryLine = new LinkedHashMap<>();
        queryLine.put("_type", "query");
        queryLine.put("chatId", chatId);
        queryLine.put("runId", runId);
        queryLine.put("updatedAt", now);
        queryLine.put("query", query);

        Map<String, Object> userStoredMessage = Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", userMessage)),
                "ts", now
        );
        Map<String, Object> assistantStoredMessage = new LinkedHashMap<>();
        assistantStoredMessage.put("role", "assistant");
        assistantStoredMessage.put("content", List.of(Map.of("type", "text", "text", assistantReply)));
        assistantStoredMessage.put("ts", now + 1);
        assistantStoredMessage.put("_contentId", runId + "_c_1");

        Map<String, Object> stepLine = new LinkedHashMap<>();
        stepLine.put("_type", "step");
        stepLine.put("chatId", chatId);
        stepLine.put("runId", runId);
        stepLine.put("_stage", "oneshot");
        stepLine.put("_seq", 1);
        stepLine.put("updatedAt", now + 1);
        stepLine.put("messages", List.of(userStoredMessage, assistantStoredMessage));

        Files.createDirectories(historyFile.getParent());
        Files.writeString(
                historyFile,
                OBJECT_MAPPER.writeValueAsString(queryLine) + System.lineSeparator()
                        + OBJECT_MAPPER.writeValueAsString(stepLine) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private Map<String, Object> reference(String id) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("id", id);
        reference.put("type", "file");
        reference.put("name", "notes.txt");
        reference.put("mimeType", "text/plain");
        reference.put("sizeBytes", 12L);
        reference.put("url", "/api/resource?file=notes.txt");
        return reference;
    }

    private Path memoryRoot() {
        return Path.of(memoryStorageProperties.getStorage().getDir());
    }

    private String todayJournalRelativePath() {
        LocalDate today = LocalDate.now();
        return "journal/" + today.getYear() + "-" + String.format("%02d", today.getMonthValue())
                + "/" + today + ".md";
    }

    private static Path prepareProvidersDir() {
        try {
            Path dir = Files.createTempDirectory("agent-platform-runner-memory-providers-");
            Files.writeString(dir.resolve("bailian.yml"), """
                    key: bailian
                    baseUrl: https://example.com/v1
                    apiKey: test-bailian-key
                    defaultModel: test-bailian-model
                    """);
            Files.writeString(dir.resolve("babelark.yml"), """
                    key: babelark
                    baseUrl: https://example.com/v1
                    apiKey: test-babelark-key
                    defaultModel: test-babelark-model
                    """);
            Files.writeString(dir.resolve("siliconflow.yml"), """
                    key: siliconflow
                    baseUrl: https://example.com/v1
                    apiKey: test-siliconflow-key
                    defaultModel: test-siliconflow-model
                    """);
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Path prepareModelsDir() {
        try {
            Path dir = Files.createTempDirectory("agent-platform-runner-memory-models-");
            Files.writeString(dir.resolve("remember-test-model.yml"), """
                    key: remember-test-model
                    provider: babelark
                    protocol: OPENAI
                    modelId: remember-model-id
                    isReasoner: true
                    isFunction: true
                    """);
            return dir;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
