package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.McpToolSyncService;
import com.linlay.agentplatform.config.ToolProperties;
import com.linlay.agentplatform.config.ViewportProperties;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.service.ChatRecordStore;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LlmCallSpec;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.service.ViewportRegistryService;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.service.SseFlushWriter;
import com.linlay.agentplatform.team.TeamProperties;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "agent.providers.bailian.base-url=https://example.com/v1",
                "agent.providers.bailian.api-key=test-bailian-key",
                "agent.providers.bailian.model=test-bailian-model",
                "agent.providers.siliconflow.base-url=https://example.com/v1",
                "agent.providers.siliconflow.api-key=test-siliconflow-key",
                "agent.providers.siliconflow.model=test-siliconflow-model",
                "agent.auth.enabled=false",
                "memory.chats.dir=${java.io.tmpdir}/springai-agent-platform-test-chats-${random.uuid}",
                "memory.chats.index.sqlite-file=${java.io.tmpdir}/springai-agent-platform-test-chats-db-${random.uuid}/chats.db",
                "agent.agents.external-dir=${user.dir}/example/agents",
                "agent.models.external-dir=${user.dir}/example/models",
                "agent.viewports.external-dir=${java.io.tmpdir}/springai-agent-platform-test-viewports-${random.uuid}",
                "agent.tools.external-dir=${java.io.tmpdir}/springai-agent-platform-test-tools-${random.uuid}",
                "agent.mcp-servers.enabled=true",
                "agent.skills.external-dir=${java.io.tmpdir}/springai-agent-platform-test-skills-${random.uuid}",
                "agent.teams.external-dir=${java.io.tmpdir}/springai-agent-platform-test-teams-${random.uuid}",
                "agent.data.external-dir=${java.io.tmpdir}/springai-agent-platform-test-data-${random.uuid}"
        }
)
@AutoConfigureWebTestClient
@Import(AgentControllerTest.TestLlmServiceConfig.class)
class AgentControllerTest {

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private FrontendSubmitCoordinator frontendSubmitCoordinator;
    @Autowired
    private ViewportProperties viewportProperties;
    @Autowired
    private ViewportRegistryService viewportRegistryService;
    @Autowired
    private ChatWindowMemoryProperties chatWindowMemoryProperties;
    @Autowired
    private ChatRecordStore chatRecordStore;
    @Autowired
    private ToolFileRegistryService toolFileRegistryService;
    @Autowired
    private ToolProperties toolProperties;
    @Autowired
    private TeamProperties teamProperties;
    @Autowired
    private TeamRegistryService teamRegistryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedFixtures() throws Exception {
        Path teamsDir = Path.of(teamProperties.getExternalDir()).toAbsolutePath().normalize();
        Files.createDirectories(teamsDir);
        Files.writeString(teamsDir.resolve("a1b2c3d4e5f6.json"), """
                {
                  "name": "Default Team",
                  "defaultAgentKey": "demoModeReact",
                  "agentKeys": ["demoModeReact"]
                }
                """);
        teamRegistryService.refreshTeams();

        Path toolsDir = Path.of(toolProperties.getExternalDir()).toAbsolutePath().normalize();
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("switch_theme.yml"), """
                {
                  "type": "function",
                  "name": "switch_theme",
                  "description": "切换主题",
                  "toolAction": true,
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "theme": {
                        "type": "string",
                        "enum": ["light", "dark"]
                      }
                    },
                    "required": ["theme"],
                    "additionalProperties": false
                  }
                }
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        toolFileRegistryService.refreshTools();
    }

    @TestConfiguration
    static class TestLlmServiceConfig {
        @Bean
        @Primary
        LlmService llmService() {
            return new LlmService() {
                @Override
                public Flux<String> streamContent(LlmCallSpec spec) {
                    String userPrompt = spec == null ? null : spec.userPrompt();
                    boolean forcedError = (userPrompt != null && userPrompt.contains("__force_stream_error__"))
                            || (spec != null && spec.messages().stream()
                            .anyMatch(message -> message != null && message.text() != null
                                    && message.text().contains("__force_stream_error__")));
                    if (forcedError) {
                        return Flux.error(new IllegalStateException("forced stream error"));
                    }
                    return Flux.just("这是", "测试", "输出");
                }

                @Override
                public Flux<LlmDelta> streamDeltas(LlmCallSpec spec) {
                    return streamContent(spec).map(content -> new LlmDelta(content, null, null));
                }

                @Override
                public Mono<String> completeText(String providerKey, String model, String systemPrompt, String userPrompt) {
                    return Mono.just("{\"thinking\":\"先分析后回答\",\"plan\":[\"步骤1\"],\"toolCalls\":[]}");
                }

                @Override
                public Mono<String> completeText(
                        String providerKey,
                        String model,
                        String systemPrompt,
                        String userPrompt,
                        String stage
                ) {
                    if (stage != null && stage.startsWith("agent-react-step-")) {
                        return Mono.just("{\"thinking\":\"信息已足够，直接生成最终回答\",\"action\":null,\"done\":true}");
                    }
                    return completeText(providerKey, model, systemPrompt, userPrompt);
                }
            };
        }

        @Bean
        @Primary
        McpToolSyncService mcpToolSyncService() {
            McpToolSyncService service = mock(McpToolSyncService.class);
            ToolDescriptor descriptor = new ToolDescriptor(
                    "mock.weather.query",
                    "天气查询",
                    "[MOCK] query weather",
                    "",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "city", Map.of("type", "string"),
                                    "date", Map.of("type", "string")
                            ),
                            "additionalProperties", true
                    ),
                    false,
                    true,
                    false,
                    null,
                    "mcp",
                    "mock",
                    null,
                    "mcp://mock"
            );
            when(service.list()).thenReturn(List.of(descriptor));
            when(service.find("mock.weather.query")).thenReturn(Optional.of(descriptor));
            return service;
        }
    }

    @Test
    void agentsShouldReturnAvailableAgents() {
        webTestClient.get()
                .uri("/api/ap/agents")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.msg").isEqualTo("success")
                .jsonPath("$.data[0].key").exists()
                .jsonPath("$.data[0].role").exists()
                .jsonPath("$.data[0].icon.name").exists()
                .jsonPath("$.data[0].icon.color").exists()
                .jsonPath("$.data[0].meta.providerType").doesNotExist()
                .jsonPath("$.data[0].meta.skills").isArray()
                .jsonPath("$.data[?(@.key=='demoModePlain')]").exists()
                .jsonPath("$.data[?(@.key=='demoModeThinking')]").exists()
                .jsonPath("$.data[?(@.key=='demoModePlainTooling')]").exists()
                .jsonPath("$.data[?(@.key=='demoModeReact')]").exists()
                .jsonPath("$.data[?(@.key=='demoModePlanExecute')]").exists()
                .jsonPath("$.data[?(@.key=='demoViewport')]").exists()
                .jsonPath("$.data[?(@.key=='demoAction')]").exists()
                .jsonPath("$.data[?(@.key=='demoAgentCreator')]").exists()
                .jsonPath("$.data[?(@.key=='demoScheduleManager')]").exists()
                .jsonPath("$.data[?(@.key=='demoMathSkill')]").exists()
                .jsonPath("$.data[?(@.key=='demoConfirmDialog')]").exists()
                .jsonPath("$.data[?(@.key=='demoDataViewer')]").exists();
    }

    @Test
    void agentsShouldSupportTagFilterByRole() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/agents").queryParam("tag", "确认对话").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.key=='demoConfirmDialog')]").exists()
                .jsonPath("$.data[?(@.key=='demoModePlain')]").doesNotExist();
    }

    @Test
    void teamsShouldReturnListAndSurfaceInvalidAgentKeys() throws Exception {
        webTestClient.get()
                .uri("/api/ap/teams")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.teamId=='a1b2c3d4e5f6')]").exists()
                .jsonPath("$.data[0].meta.invalidAgentKeys").isArray()
                .jsonPath("$.data[0].meta.defaultAgentKey").isEqualTo("demoModeReact")
                .jsonPath("$.data[0].meta.defaultAgentKeyValid").isEqualTo(true);

        Path teamsDir = Path.of(teamProperties.getExternalDir()).toAbsolutePath().normalize();
        Files.createDirectories(teamsDir);
        Files.writeString(teamsDir.resolve("bbccddeeff00.json"), """
                {
                  "name": "Invalid Team",
                  "defaultAgentKey": "missing_agent",
                  "agentKeys": ["missing_agent", "demoModePlain"]
                }
                """);
        teamRegistryService.refreshTeams();

        String body = webTestClient.get()
                .uri("/api/ap/teams")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotBlank();
        Map<String, Object> root = objectMapper.readValue(body, new TypeReference<>() {
        });
        List<Map<String, Object>> teams = objectMapper.convertValue(root.get("data"), new TypeReference<>() {
        });
        Map<String, Object> invalidTeam = teams.stream()
                .filter(item -> "bbccddeeff00".equals(item.get("teamId")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) invalidTeam.get("meta");
        assertThat(meta).isNotNull();
        assertThat(meta.get("defaultAgentKey")).isEqualTo("missing_agent");
        assertThat(meta.get("defaultAgentKeyValid")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<String> invalidAgentKeys = (List<String>) meta.get("invalidAgentKeys");
        assertThat(invalidAgentKeys).contains("missing_agent");
    }

    @Test
    void skillsShouldReturnListAndSupportTag() {
        webTestClient.get()
                .uri("/api/ap/skills")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].key").exists()
                .jsonPath("$.data[0].meta.promptTruncated").isBoolean()
                .jsonPath("$.data[?(@.key=='math_basic')]").exists()
                .jsonPath("$.data[?(@.key=='slack-gif-creator')]").exists();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/skills").queryParam("tag", "slack-gif").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.key=='slack-gif-creator')]").exists()
                .jsonPath("$.data[?(@.key=='math_basic')]").doesNotExist();
    }

    @Test
    void removedAgentAndSkillSingleEndpointsShouldReturnNotFound() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/agent").queryParam("agentKey", "demoModePlanExecute").build())
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/skill").queryParam("skillId", "math_basic").build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void toolDetailShouldReturnLabelAndMetadata() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/tool").queryParam("toolName", "mock.weather.query").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.key").isEqualTo("mock.weather.query")
                .jsonPath("$.data.name").isEqualTo("mock.weather.query")
                .jsonPath("$.data.label").isEqualTo("天气查询")
                .jsonPath("$.data.meta.sourceType").isEqualTo("mcp")
                .jsonPath("$.data.meta.sourceKey").isEqualTo("mock")
                .jsonPath("$.data.meta.toolApi").doesNotExist();
    }

    @Test
    void toolsShouldReturnListAndSupportKindAndTag() throws Exception {
        String body = webTestClient.get()
                .uri("/api/ap/tools")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertThat(data).isNotNull();
        Map<String, Object> datetimeTool = data.stream()
                .filter(item -> "datetime".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(datetimeTool.get("label")).isEqualTo("日期时间");

        webTestClient.get()
                .uri("/api/ap/tools")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].key").exists()
                .jsonPath("$.data[0].meta.kind").exists()
                .jsonPath("$.data[0].meta.sourceType").exists()
                .jsonPath("$.data[?(@.key=='datetime')]").exists()
                .jsonPath("$.data[?(@.key=='confirm_dialog')]").exists()
                .jsonPath("$.data[?(@.key=='switch_theme')]").exists();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/tools").queryParam("kind", "frontend").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.key=='confirm_dialog')]").exists()
                .jsonPath("$.data[?(@.key=='datetime')]").doesNotExist();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/tools")
                        .queryParam("kind", "backend")
                        .queryParam("tag", "weather")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[?(@.key=='mock.weather.query')]").exists();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/tools").queryParam("kind", "invalid").build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void toolsShouldExposeMcpSourceMetaWhenMcpToolExists() throws Exception {
        String body = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/tools")
                        .queryParam("kind", "backend")
                        .queryParam("tag", "mock.weather.query")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("data");
        assertThat(data).isNotNull();

        Map<String, Object> mcpTool = data.stream()
                .filter(item -> "mock.weather.query".equals(item.get("key")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) mcpTool.get("meta");
        assertThat(meta.get("sourceType")).isEqualTo("mcp");
        assertThat(meta.get("sourceKey")).isEqualTo("mock");
        assertThat(meta).doesNotContainKey("toolApi");
        assertThat(mcpTool.get("label")).isEqualTo("天气查询");
    }

    @Test
    void queryShouldStreamByDefaultWithoutAcceptOrStreamParam() {
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlanExecute",
                        "message", "查询上海天气"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(joined).contains("\"type\":\"request.query\"");
        assertThat(joined).contains("\"type\":\"run.start\"");
        assertThat(joined).contains("\"type\":\"run.complete\"");
        assertThat(countOccurrences(joined, "[DONE]")).isEqualTo(1);
        int runCompleteIndex = joined.lastIndexOf("\"type\":\"run.complete\"");
        int doneIndex = joined.lastIndexOf("[DONE]");
        assertThat(runCompleteIndex).isGreaterThanOrEqualTo(0);
        assertThat(doneIndex).isGreaterThan(runCompleteIndex);
        assertThat(joined.trim()).endsWith("[DONE]");

        String requestId = extractFirstValue(joined, "requestId");
        String runId = extractFirstValue(joined, "runId");
        assertThat(requestId).isNotBlank();
        assertThat(runId).isNotBlank();
        assertThat(runId).matches("^[0-9a-z]+$");
        assertThat(requestId).isEqualTo(runId);
    }

    @Test
    void queryShouldAppendDoneSentinelAfterRunError() {
        AgentQueryService queryService = mock(AgentQueryService.class);
        SseFlushWriter flushWriter = mock(SseFlushWriter.class);
        AtomicReference<Flux<ServerSentEvent<String>>> writtenStreamRef = new AtomicReference<>();
        when(flushWriter.write(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            writtenStreamRef.set(invocation.getArgument(1));
            return Mono.empty();
        });

        String requestId = "req_error_001";
        String chatId = UUID.randomUUID().toString();
        String runId = "run_error_001";
        StreamRequest.Query streamRequest = new StreamRequest.Query(
                requestId,
                chatId,
                "user",
                "trigger run error",
                "demoModePlain",
                null,
                null,
                null,
                null,
                true,
                "chat",
                runId
        );
        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                mock(com.linlay.agentplatform.agent.Agent.class),
                streamRequest,
                new AgentRequest("trigger run error", chatId, requestId, runId, Map.of())
        );
        when(queryService.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(session);
        when(queryService.stream(org.mockito.ArgumentMatchers.any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder()
                        .event("message")
                        .data("{\"type\":\"run.error\",\"runId\":\"run_error_001\",\"timestamp\":1,\"error\":{\"message\":\"boom\"}}")
                        .build()
        ));

        AgentController controller = new AgentController(
                mock(com.linlay.agentplatform.agent.AgentRegistry.class),
                queryService,
                mock(ChatRecordStore.class),
                flushWriter,
                mock(ViewportRegistryService.class),
                mock(com.linlay.agentplatform.service.McpViewportService.class),
                mock(FrontendSubmitCoordinator.class),
                mock(com.linlay.agentplatform.security.ChatImageTokenService.class),
                mock(com.linlay.agentplatform.skill.SkillRegistryService.class),
                mock(TeamRegistryService.class),
                mock(ToolRegistry.class),
                new com.linlay.agentplatform.config.LoggingAgentProperties(),
                new ObjectMapper()
        );

        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/ap/query").build()
        );
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        QueryRequest request = new QueryRequest(
                requestId,
                chatId,
                "demoModePlain",
                null,
                "user",
                "trigger run error",
                null,
                null,
                null,
                true
        );

        controller.query(request, response, exchange).block(Duration.ofSeconds(2));

        Flux<ServerSentEvent<String>> writtenStream = writtenStreamRef.get();
        assertThat(writtenStream).isNotNull();
        List<ServerSentEvent<String>> events = writtenStream.collectList().block(Duration.ofSeconds(2));
        assertThat(events).isNotNull();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).contains("\"type\":\"run.error\"");
        assertThat(events.get(1).event()).isEqualTo("message");
        assertThat(events.get(1).data()).isEqualTo("[DONE]");
    }

    @Test
    void queryShouldEmitContentDeltaPerUpstreamChunkForPlainAgent() {
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlain",
                        "message", "流式测试"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(800)
                .collectList()
                .block(Duration.ofSeconds(8));

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(countOccurrences(joined, "\"type\":\"content.delta\"")).isEqualTo(3);
        int lastContentIndex = joined.lastIndexOf("\"type\":\"content.delta\"");
        int runCompleteIndex = joined.lastIndexOf("\"type\":\"run.complete\"");
        assertThat(lastContentIndex).isGreaterThanOrEqualTo(0);
        assertThat(runCompleteIndex).isGreaterThan(lastContentIndex);
    }

    @Test
    void queryShouldUseUuidChatIdWhenProvided() {
        String chatId = "123e4567-e89b-12d3-a456-426614174000";
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "requestId", "req_001",
                        "chatId", chatId,
                        "agentKey", "demoModePlanExecute",
                        "message", "查询上海天气"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(joined).contains("\"type\":\"request.query\"");
        assertThat(joined).contains("\"type\":\"run.start\"");
        assertThat(joined).contains("\"type\":\"run.complete\"");
        assertThat(joined).contains("\"chatId\":\"" + chatId + "\"");
    }

    @Test
    void queryShouldPropagateTeamIdToSseAndChatRecords() throws Exception {
        String teamId = "a1b2c3d4e5f6";
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModeReact",
                        "teamId", teamId,
                        "message", "team hello"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(joined).contains("\"type\":\"request.query\"");
        assertThat(joined).contains("\"teamId\":\"" + teamId + "\"");

        String chatId = extractFirstValue(joined, "chatId");
        assertThat(chatId).isNotBlank();

        byte[] chatsBody = webTestClient.get()
                .uri("/api/ap/chats")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(chatsBody).isNotNull();
        Map<String, Object> chatsRoot = objectMapper.readValue(chatsBody, new TypeReference<>() {
        });
        List<Map<String, Object>> chats = objectMapper.convertValue(chatsRoot.get("data"), new TypeReference<>() {
        });
        Map<String, Object> chat = chats.stream()
                .filter(item -> chatId.equals(String.valueOf(item.get("chatId"))))
                .findFirst()
                .orElseThrow();
        assertThat(chat.get("teamId")).isEqualTo(teamId);
        assertThat(chat.get("agentKey")).isNull();

        byte[] detailBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/chat").queryParam("chatId", chatId).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(detailBody).isNotNull();
        Map<String, Object> detailRoot = objectMapper.readValue(detailBody, new TypeReference<>() {
        });
        Map<String, Object> data = objectMapper.convertValue(detailRoot.get("data"), new TypeReference<>() {
        });
        List<Map<String, Object>> events = objectMapper.convertValue(data.get("events"), new TypeReference<>() {
        });
        Map<String, Object> requestQuery = events.stream()
                .filter(event -> "request.query".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(requestQuery).containsEntry("teamId", teamId);
    }

    @Test
    void queryShouldRejectTeamIdWithoutAgentKey() {
        webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "teamId", "a1b2c3d4e5f6",
                        "message", "missing agent"
                ))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void queryShouldRejectUnknownTeamId() {
        webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "teamId", "deadbeefcafe",
                        "agentKey", "demoModeReact",
                        "message", "unknown team"
                ))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void queryShouldRejectAgentOutsideTeam() {
        webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "teamId", "a1b2c3d4e5f6",
                        "agentKey", "demoModePlanExecute",
                        "message", "outside team"
                ))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void queryShouldRejectInvalidChatId() {
        webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlanExecute",
                        "chatId", "not-a-uuid",
                        "message", "查询上海天气"
                ))
                .exchange()
                .expectStatus().isBadRequest();
                // query 接口为 SSE，错误时只校验状态码
    }

    @Test
    void submitShouldReturnAcceptedAck() {
        String runId = "123e4567-e89b-12d3-a456-426614174001";
        String toolId = "tool_abc";
        Mono<Object> pending = frontendSubmitCoordinator.awaitSubmit(runId, toolId).cache();
        pending.subscribe();

        webTestClient.post()
                .uri("/api/ap/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "runId", runId,
                        "toolId", toolId,
                        "params", Map.of("confirmed", true)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.msg").isEqualTo("success")
                .jsonPath("$.data.accepted").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("accepted")
                .jsonPath("$.data.runId").isEqualTo(runId)
                .jsonPath("$.data.toolId").isEqualTo(toolId)
                .jsonPath("$.data.detail").isNotEmpty();

        assertThat(pending.block(Duration.ofSeconds(2))).isEqualTo(Map.of("confirmed", true));
    }

    @Test
    void submitShouldReturnUnmatchedWhenNoPendingTool() {
        String runId = "123e4567-e89b-12d3-a456-426614174111";
        String toolId = "tool_missing";

        webTestClient.post()
                .uri("/api/ap/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "runId", runId,
                        "toolId", toolId,
                        "params", Map.of("confirmed", true)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.msg").isEqualTo("success")
                .jsonPath("$.data.accepted").isEqualTo(false)
                .jsonPath("$.data.status").isEqualTo("unmatched")
                .jsonPath("$.data.runId").isEqualTo(runId)
                .jsonPath("$.data.toolId").isEqualTo(toolId)
                .jsonPath("$.data.detail").isNotEmpty();
    }

    @Test
    void viewportShouldReturnHtmlData() throws Exception {
        Path viewportDir = Path.of(viewportProperties.getExternalDir());
        Files.createDirectories(viewportDir);
        Files.writeString(viewportDir.resolve("weather_card.html"), "<div>sunny</div>");
        viewportRegistryService.refreshViewports();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/viewport")
                        .queryParam("viewportKey", "weather_card")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.html").isEqualTo("<div>sunny</div>");
    }

    @Test
    void viewportShouldReturnQlcJsonData() throws Exception {
        Path viewportDir = Path.of(viewportProperties.getExternalDir());
        Files.createDirectories(viewportDir);
        Files.writeString(viewportDir.resolve("flight_form.qlc"), "{\"schema\":{\"type\":\"object\"},\"packages\":[\"pkg-a\"]}");
        viewportRegistryService.refreshViewports();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/viewport")
                        .queryParam("viewportKey", "flight_form")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.schema.type").isEqualTo("object")
                .jsonPath("$.data.packages[0]").isEqualTo("pkg-a");
    }

    @Test
    void viewportShouldReturn404WhenMissing() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/viewport")
                        .queryParam("viewportKey", "missing_viewport")
                        .build())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }

    @Test
    void chatsAndChatApisShouldReturnStoredChatSnapshot() {
        String message = "0123456789ABCDEFGHI";
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlanExecute",
                        "message", message,
                        "references", List.of(Map.of(
                                "id", "ref_001",
                                "type", "url",
                                "name", "doc",
                                "url", "https://example.com/ref"
                        ))
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(chunks).isNotNull();

        String joined = String.join("", chunks);
        String chatId = extractFirstValue(joined, "chatId");
        String runId = extractFirstValue(joined, "runId");
        assertThat(chatId).isNotBlank();
        assertThat(runId).isNotBlank();

        webTestClient.get()
                .uri("/api/ap/chats")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].chatId").isEqualTo(chatId)
                .jsonPath("$.data[0].chatName").isEqualTo(message)
                .jsonPath("$.data[0].agentKey").isEqualTo("demoModePlanExecute")
                .jsonPath("$.data[0].teamId").value(value -> assertThat(value).isNull())
                .jsonPath("$.data[0].createdAt").isNumber()
                .jsonPath("$.data[0].updatedAt").isNumber()
                .jsonPath("$.data[0].lastRunId").isEqualTo(runId)
                .jsonPath("$.data[0].lastRunContent").isNotEmpty()
                .jsonPath("$.data[0].readStatus").isEqualTo(0);

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", chatId)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.msg").isEqualTo("success")
                .jsonPath("$.data.chatId").isEqualTo(chatId)
                .jsonPath("$.data.chatName").isEqualTo(message)
                .jsonPath("$.data.rawMessages").doesNotExist()
                .jsonPath("$.data.events[?(@.type=='request.query')]").exists()
                .jsonPath("$.data.events[?(@.type=='run.start')]").exists()
                .jsonPath("$.data.events[?(@.type=='content.snapshot')]").exists()
                .jsonPath("$.data.events[?(@.type=='run.complete')]").exists();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", chatId)
                        .queryParam("includeRawMessages", true)
                .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.rawMessages[0].role").isEqualTo("user")
                .jsonPath("$.data.events[?(@.type=='request.query')]").exists();
    }

    @Test
    void chatReplayShouldNotContainDoneSentinel() {
        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlain",
                        "message", "chat replay done sentinel check"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(800)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(chunks).isNotNull();

        String chatId = extractFirstValue(String.join("", chunks), "chatId");
        assertThat(chatId).isNotBlank();

        byte[] responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", chatId)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(responseBody).isNotNull();
        String json = new String(responseBody, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("[DONE]");
    }

    @Test
    void readAndIncrementalChatsApisShouldWork() throws Exception {
        FluxExchangeResult<String> firstResult = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlain",
                        "message", "agent list smoke"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        List<String> firstChunks = firstResult.getResponseBody()
                .take(800)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(firstChunks).isNotNull();
        String firstJoined = String.join("", firstChunks);
        String chatId = extractFirstValue(firstJoined, "chatId");
        String firstRunId = extractFirstValue(firstJoined, "runId");
        assertThat(chatId).isNotBlank();
        assertThat(firstRunId).isNotBlank();

        Thread.sleep(20L);
        FluxExchangeResult<String> secondResult = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "chatId", chatId,
                        "agentKey", "demoModePlain",
                        "message", "agent list smoke second"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        List<String> secondChunks = secondResult.getResponseBody()
                .take(800)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(secondChunks).isNotNull();
        String secondJoined = String.join("", secondChunks);
        String secondRunId = extractFirstValue(secondJoined, "runId");
        assertThat(secondRunId).isNotBlank();

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chats")
                        .queryParam("lastRunId", firstRunId)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].chatId").isEqualTo(chatId)
                .jsonPath("$.data[0].lastRunId").isEqualTo(secondRunId)
                .jsonPath("$.data[0].teamId").value(value -> assertThat(value).isNull())
                .jsonPath("$.data[0].readStatus").isEqualTo(0);

        webTestClient.post()
                .uri("/api/ap/read")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("chatId", chatId))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.chatId").isEqualTo(chatId)
                .jsonPath("$.data.readStatus").isEqualTo(1)
                .jsonPath("$.data.readAt").isNumber();
    }

    @Test
    void chatsApiShouldSupportAgentKeyFilterTogetherWithIncrementalLastRunId() throws Exception {
        FluxExchangeResult<String> plainResult = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlain",
                        "message", "plain filter smoke"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        List<String> plainChunks = plainResult.getResponseBody()
                .take(800)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(plainChunks).isNotNull();
        String plainJoined = String.join("", plainChunks);
        String plainChatId = extractFirstValue(plainJoined, "chatId");
        assertThat(plainChatId).isNotBlank();

        FluxExchangeResult<String> firstPlanResult = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlanExecute",
                        "message", "plan filter smoke"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        List<String> firstPlanChunks = firstPlanResult.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(firstPlanChunks).isNotNull();
        String firstPlanJoined = String.join("", firstPlanChunks);
        String planChatId = extractFirstValue(firstPlanJoined, "chatId");
        String firstPlanRunId = extractFirstValue(firstPlanJoined, "runId");
        assertThat(planChatId).isNotBlank();
        assertThat(firstPlanRunId).isNotBlank();

        Thread.sleep(20L);
        FluxExchangeResult<String> secondPlanResult = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "chatId", planChatId,
                        "agentKey", "demoModePlanExecute",
                        "message", "plan filter smoke second"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        List<String> secondPlanChunks = secondPlanResult.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(secondPlanChunks).isNotNull();
        String secondPlanJoined = String.join("", secondPlanChunks);
        String secondPlanRunId = extractFirstValue(secondPlanJoined, "runId");
        assertThat(secondPlanRunId).isNotBlank();

        byte[] body = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chats")
                        .queryParam("lastRunId", firstPlanRunId)
                        .queryParam("agentKey", "demoModePlanExecute")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();

        Map<String, Object> root = objectMapper.readValue(body, new TypeReference<>() {
        });
        List<Map<String, Object>> chats = objectMapper.convertValue(root.get("data"), new TypeReference<>() {
        });
        assertThat(chats).isNotEmpty();
        assertThat(chats).allMatch(item -> "demoModePlanExecute".equals(item.get("agentKey")));
        assertThat(chats.stream().map(item -> String.valueOf(item.get("chatId"))).toList()).contains(planChatId);
        assertThat(chats.stream().map(item -> String.valueOf(item.get("chatId"))).toList()).doesNotContain(plainChatId);
        assertThat(chats.stream().map(item -> String.valueOf(item.get("lastRunId"))).toList()).contains(secondPlanRunId);
    }

    @Test
    void readApiShouldRejectBlankChatId() {
        webTestClient.post()
                .uri("/api/ap/read")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("chatId", ""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void chatShouldRejectInvalidChatId() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", "not-a-uuid")
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void chatShouldIgnoreDeprecatedIncludeEventsParam() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", "123e4567-e89b-12d3-a456-426614174000")
                        .queryParam("includeEvents", true)
                        .build())
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(400));
    }

    @Test
    void chatShouldReturnNotFoundWhenChatMissing() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", "123e4567-e89b-12d3-a456-426614174099")
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void chatDetailShouldContainSingleChatStartAcrossMultipleRuns() {
        FluxExchangeResult<String> firstRun = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "agentKey", "demoModePlanExecute",
                        "message", "第一轮"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        List<String> firstChunks = firstRun.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(firstChunks).isNotNull();
        String chatId = extractFirstValue(String.join("", firstChunks), "chatId");
        assertThat(chatId).isNotBlank();

        FluxExchangeResult<String> secondRun = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "chatId", chatId,
                        "agentKey", "demoModePlanExecute",
                        "message", "第二轮"
                ))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        List<String> secondChunks = secondRun.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));
        assertThat(secondChunks).isNotNull();

        byte[] responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", chatId)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).isNotNull();
        String json = new String(responseBody, StandardCharsets.UTF_8);
        assertThat(countOccurrences(json, "\"type\":\"chat.start\"")).isEqualTo(1);
        assertThat(countOccurrences(json, "\"type\":\"run.start\"")).isGreaterThanOrEqualTo(2);
        assertThat(json).doesNotContain("\"type\":\"chat.update\"");
    }

    @Test
    void chatApiShouldReturnPlanUpdateWithSeq() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174088";
        Path chatDir = Path.of(chatWindowMemoryProperties.getDir());
        Files.createDirectories(chatDir);
        chatRecordStore.ensureChat(chatId, "demoModePlanExecute", "星策", "测试计划");

        Map<String, Object> queryLine = new LinkedHashMap<>();
        queryLine.put("_type", "query");
        queryLine.put("chatId", chatId);
        queryLine.put("runId", "run_plan_001");
        queryLine.put("updatedAt", 1707000600000L);
        queryLine.put("query", Map.of(
                "requestId", "req_plan_001",
                "chatId", chatId,
                "role", "user",
                "message", "测试计划",
                "stream", true
        ));
        writeJsonLine(chatDir.resolve(chatId + ".json"), queryLine);

        Map<String, Object> stepLine = new LinkedHashMap<>();
        stepLine.put("_type", "step");
        stepLine.put("chatId", chatId);
        stepLine.put("runId", "run_plan_001");
        stepLine.put("_stage", "plan");
        stepLine.put("_seq", 1);
        stepLine.put("updatedAt", 1707000600000L);
        stepLine.put("messages", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "text", "text", "测试计划")),
                        "ts", 1707000600000L
                ),
                Map.of(
                        "role", "assistant",
                        "content", List.of(Map.of("type", "text", "text", "已创建计划")),
                        "ts", 1707000600001L
                )
        ));
        stepLine.put("plan", Map.of(
                "planId", "plan_chat_001",
                "tasks", List.of(
                        Map.of("taskId", "task0", "description", "收集信息", "status", "init"),
                        Map.of("taskId", "task1", "description", "执行任务", "status", "in_progress")
                )
        ));
        writeJsonLine(chatDir.resolve(chatId + ".json"), stepLine);

        byte[] responseBody = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/ap/chat")
                        .queryParam("chatId", chatId)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).isNotNull();
        Map<String, Object> root = objectMapper.readValue(responseBody, new TypeReference<>() {
        });
        Map<String, Object> data = objectMapper.convertValue(root.get("data"), new TypeReference<>() {
        });
        List<Map<String, Object>> events = objectMapper.convertValue(data.get("events"), new TypeReference<>() {
        });

        Map<String, Object> planUpdate = events.stream()
                .filter(event -> "plan.update".equals(event.get("type")))
                .findFirst()
                .orElseThrow();

        assertThat(planUpdate).containsEntry("type", "plan.update");
        assertThat(planUpdate).containsEntry("planId", "plan_chat_001");
        assertThat(planUpdate).containsEntry("chatId", chatId);
        assertThat(planUpdate).containsKey("plan");
        assertThat(planUpdate).containsKey("timestamp");
        assertThat(planUpdate).containsKey("seq");
        assertThat(planUpdate.get("seq")).isInstanceOf(Number.class);
    }

    @Test
    void queryShouldEmitPlanUpdateSseWithSeq() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174089";
        Path chatDir = Path.of(chatWindowMemoryProperties.getDir());
        Files.createDirectories(chatDir);
        chatRecordStore.ensureChat(chatId, "demoModePlanExecute", "星策", "初始化计划");

        Map<String, Object> queryLine = new LinkedHashMap<>();
        queryLine.put("_type", "query");
        queryLine.put("chatId", chatId);
        queryLine.put("runId", "run_plan_seed_001");
        queryLine.put("updatedAt", 1707000700000L);
        queryLine.put("query", Map.of(
                "requestId", "req_plan_seed_001",
                "chatId", chatId,
                "role", "user",
                "message", "初始化计划",
                "stream", true
        ));
        writeJsonLine(chatDir.resolve(chatId + ".json"), queryLine);

        Map<String, Object> stepLine = new LinkedHashMap<>();
        stepLine.put("_type", "step");
        stepLine.put("chatId", chatId);
        stepLine.put("runId", "run_plan_seed_001");
        stepLine.put("_stage", "plan");
        stepLine.put("_seq", 1);
        stepLine.put("updatedAt", 1707000700000L);
        stepLine.put("messages", List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "text", "text", "初始化计划")),
                        "ts", 1707000700000L
                ),
                Map.of(
                        "role", "assistant",
                        "content", List.of(Map.of("type", "text", "text", "种子计划")),
                        "ts", 1707000700001L
                )
        ));
        stepLine.put("plan", Map.of(
                "planId", "plan_chat_seed_001",
                "tasks", List.of(
                        Map.of("taskId", "task0", "description", "收集信息", "status", "init"),
                        Map.of("taskId", "task1", "description", "执行任务", "status", "in_progress")
                )
        ));
        writeJsonLine(chatDir.resolve(chatId + ".json"), stepLine);

        FluxExchangeResult<String> result = webTestClient.post()
                .uri("/api/ap/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "chatId", chatId,
                        "agentKey", "demoModePlanExecute",
                        "message", "第二轮"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        List<String> chunks = result.getResponseBody()
                .take(1600)
                .collectList()
                .block(Duration.ofSeconds(8));

        assertThat(chunks).isNotNull();
        String joined = String.join("", chunks);
        assertThat(joined).contains("\"type\":\"plan.update\"");

        String planPayload = extractTypedJsonObject(joined, "plan.update");
        assertThat(planPayload).isNotBlank();
        assertThat(planPayload).contains("\"type\":\"plan.update\"");
        assertThat(planPayload).contains("\"seq\":");
    }

    private String extractFirstValue(String text, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int start = 0;
        while (true) {
            int index = text.indexOf(token, start);
            if (index < 0) {
                return count;
            }
            count++;
            start = index + token.length();
        }
    }

    private String extractTypedJsonObject(String text, String type) {
        String marker = "\"type\":\"" + type + "\"";
        int markerIndex = text.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int start = text.lastIndexOf("{", markerIndex);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
                continue;
            }
            if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private void writeJsonLine(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        String line = objectMapper.writeValueAsString(value) + System.lineSeparator();
        Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
