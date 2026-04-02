package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.PlannedToolCall;
import com.linlay.agentplatform.agent.ToolArgumentResolver;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.config.properties.FrontendToolProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.ArtifactEventPayload;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.chat.ArtifactPublishService;
import com.linlay.agentplatform.service.chat.ChatAssetCatalogService;
import com.linlay.agentplatform.service.chat.ChatDataPathService;
import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamEnvelope;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.service.AgentDeltaToStreamInputMapper;
import com.linlay.agentplatform.stream.service.StreamEventAssembler;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitCoordinator;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.PublishArtifactTool;
import com.linlay.agentplatform.tool.SystemPlanGetTasks;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolMetadataAware;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class ToolExecutionServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void planGetShouldReturnCurrentPlanState() {
        SystemPlanGetTasks planGetTool = new SystemPlanGetTasks();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(planGetTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                new LocalToolInvoker(toolRegistry)
        );

        ExecutionContext context = executionContext(
                definition(),
                new AgentRequest("test", "chat_plan_1", null, "run_plan_1"),
                List.of()
        );
        context.initializePlan(
                "plan_chat_1",
                List.of(
                        new AgentDelta.PlanTask("task1", "检查环境", "init"),
                        new AgentDelta.PlanTask("task2", "执行迁移", "init")
                )
        );

        Map<String, BaseTool> enabledTools = new LinkedHashMap<>();
        for (BaseTool tool : toolRegistry.list()) {
            enabledTools.put(tool.name(), tool);
        }

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("_plan_get_tasks_", Map.of(), "call_plan_get_tasks_1")),
                Map.copyOf(enabledTools),
                new ArrayList<>(),
                "run_plan_1",
                context,
                false
        );

        assertThat(batch.events()).hasSize(1);
        String result = batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .filter(item -> "call_plan_get_tasks_1".equals(item.toolId()))
                .map(AgentDelta.ToolResult::result)
                .findFirst()
                .orElseThrow();

        assertThat(result)
                .contains("计划ID: plan_chat_1")
                .contains("task1 | init | 检查环境")
                .contains("task2 | init | 执行迁移")
                .contains("当前应执行 taskId: task1");
    }

    @Test
    void backendRuntimeFailureShouldRetryByToolBudget() throws Exception {
        FlakyTool flakyTool = new FlakyTool("flaky_tool", 2, "OK");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(flakyTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                new LocalToolInvoker(toolRegistry)
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 500L, 2)
        );
        ExecutionContext context = executionContext(
                definition(List.of("flaky_tool"), budget),
                new AgentRequest("test", "chat_retry_1", null, "run_retry_1"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("flaky_tool", Map.of(), "call_retry_1")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_1",
                context,
                false
        );

        assertThat(flakyTool.attempts()).isEqualTo(3);
        assertThat(singleToolResult(batch, "call_retry_1")).isEqualTo("OK");
    }

    @Test
    void shouldNotEmitToolEndsWhenToolCallsWereAlreadyStreamed() {
        ConstantTool firstTool = new ConstantTool("first_tool", "FIRST_OK");
        ConstantTool secondTool = new ConstantTool("second_tool", "SECOND_OK");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(firstTool, secondTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                new LocalToolInvoker(toolRegistry)
        );

        ExecutionContext context = executionContext(
                definition(List.of("first_tool", "second_tool"), Budget.DEFAULT),
                new AgentRequest("test", "chat_end_order_1", null, "run_end_order_1"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(
                        new PlannedToolCall("first_tool", Map.of(), "call_first"),
                        new PlannedToolCall("second_tool", Map.of(), "call_second")
                ),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_end_order_1",
                context,
                false
        );

        assertThat(batch.deltas().stream().flatMap(delta -> delta.toolEnds().stream()).toList()).isEmpty();
        assertThat(singleToolResult(batch, "call_first")).isEqualTo("FIRST_OK");
        assertThat(singleToolResult(batch, "call_second")).isEqualTo("SECOND_OK");
    }

    @Test
    void shouldWaitForFrontendSubmitWithoutPreExecutionToolEnd() throws Exception {
        ConstantTool frontendTool = new ConstantTool("confirm_dialog", "IGNORED", frontendDescriptor("confirm_dialog"));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(frontendTool));

        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(5_000L);
        FrontendSubmitCoordinator submitCoordinator = new FrontendSubmitCoordinator(frontendToolProperties);

        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                submitCoordinator,
                null,
                null
        );

        ExecutionContext context = executionContext(
                definition(List.of("confirm_dialog"), Budget.DEFAULT),
                new AgentRequest("test", "chat_frontend_1", null, "run_frontend_1"),
                List.of()
        );

        List<AgentDelta> preExecutionDeltas = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolExecutionService.ToolExecutionBatch> future = executor.submit(() ->
                    toolExecutionService.executeToolCalls(
                            List.of(new PlannedToolCall("confirm_dialog", Map.of("question", "去哪玩"), "call_frontend_1")),
                            enabledTools(toolRegistry),
                            new ArrayList<>(),
                            "run_frontend_1",
                            context,
                            false,
                            null,
                            preExecutionDeltas::add
                    ));

            Thread.sleep(100L);
            assertThat(preExecutionDeltas).isEmpty();
            assertThat(future.isDone()).isFalse();

            FrontendSubmitCoordinator.SubmitAck ack = submitCoordinator.submit(
                    "run_frontend_1",
                    "call_frontend_1",
                    Map.of("choice", "自然风光")
            );
            assertThat(ack.accepted()).isTrue();

            ToolExecutionService.ToolExecutionBatch batch = future.get(2, TimeUnit.SECONDS);
            assertThat(batch.deltas().stream().flatMap(delta -> delta.toolEnds().stream()).toList()).isEmpty();
            assertThat(preExecutionDeltas.stream().flatMap(delta -> delta.toolEnds().stream()).toList()).isEmpty();
            assertThat(singleToolResult(batch, "call_frontend_1")).isEqualTo("{\"choice\":\"自然风光\"}");
            assertThat(preExecutionDeltas.stream()
                    .map(AgentDelta::requestSubmit)
                    .filter(Objects::nonNull)
                    .anyMatch(submit -> "run_frontend_1".equals(submit.runId())
                            && "call_frontend_1".equals(submit.toolId())))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStreamVisibleToolLifecycleOneByOneWhenToolCallsAreEmitted() {
        ConstantTool firstTool = new ConstantTool("first_tool", "FIRST_OK");
        ConstantTool secondTool = new ConstantTool("second_tool", "SECOND_OK");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(firstTool, secondTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                new LocalToolInvoker(toolRegistry)
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(
                        new PlannedToolCall("first_tool", Map.of(), "call_first_visible"),
                        new PlannedToolCall("second_tool", Map.of(), "call_second_visible")
                ),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_visible_tool_order_1",
                executionContext(
                        definition(List.of("first_tool", "second_tool"), Budget.DEFAULT),
                        new AgentRequest("test", "chat_visible_tool_order_1", null, "run_visible_tool_order_1"),
                        List.of()
                ),
                true
        );

        List<StreamEvent> events = assembleEvents(toolRegistry, "chat_visible_tool_order_1", "run_visible_tool_order_1", batch.deltas());
        int firstStart = indexOfEvent(events, "tool.start", "toolId", "call_first_visible");
        int firstResult = indexOfEvent(events, "tool.result", "toolId", "call_first_visible");
        int secondStart = indexOfEvent(events, "tool.start", "toolId", "call_second_visible");
        int secondResult = indexOfEvent(events, "tool.result", "toolId", "call_second_visible");

        assertThat(firstStart).isGreaterThanOrEqualTo(0);
        assertThat(firstResult).isGreaterThan(firstStart);
        assertThat(secondStart).isGreaterThan(firstResult);
        assertThat(secondResult).isGreaterThan(secondStart);
    }

    @Test
    void frontendTimeoutResultShouldContainStructuredTimeoutCode() throws Exception {
        ConstantTool frontendTool = new ConstantTool("confirm_dialog", "IGNORED", frontendDescriptor("confirm_dialog"));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(frontendTool));

        FrontendToolProperties frontendToolProperties = new FrontendToolProperties();
        frontendToolProperties.setSubmitTimeoutMs(60L);
        FrontendSubmitCoordinator submitCoordinator = new FrontendSubmitCoordinator(frontendToolProperties);

        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                submitCoordinator,
                null,
                null
        );

        ExecutionContext context = executionContext(
                definition(List.of("confirm_dialog"), Budget.DEFAULT),
                new AgentRequest("test", "chat_frontend_timeout", null, "run_frontend_timeout"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("confirm_dialog", Map.of("question", "去哪玩"), "call_frontend_timeout_1")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_frontend_timeout",
                context,
                false
        );

        String result = singleToolResult(batch, "call_frontend_timeout_1");
        com.fasterxml.jackson.databind.JsonNode resultNode = objectMapper.readTree(result);
        assertThat(resultNode.path("ok").asBoolean(true)).isFalse();
        assertThat(resultNode.path("code").asText()).isEqualTo(ToolExecutionService.FRONTEND_SUBMIT_TIMEOUT_CODE);
        assertThat(resultNode.path("error").asText()).contains("Frontend tool submit timeout");
        assertThat(resultNode.path("scope").asText()).isEqualTo("frontend_submit");
        assertThat(resultNode.path("category").asText()).isEqualTo("timeout");
        assertThat(resultNode.path("diagnostics").path("timeoutMs").asLong()).isEqualTo(60L);
    }

    @Test
    void mcpViewportToolShouldExecuteWithoutWaitingForFrontendSubmit() {
        ConstantTool backendMetadataTool = new ConstantTool("mock.weather.query", "IGNORED", mcpViewportDescriptor("mock.weather.query", "weather_card"));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(backendMetadataTool));

        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                new LoggingAgentProperties(),
                (name, args, context) -> objectMapper.valueToTree(Map.of("ok", true, "source", "mcp"))
        );

        ExecutionContext context = executionContext(
                definition(List.of("mock.weather.query"), Budget.DEFAULT),
                new AgentRequest("test", "chat_mcp_viewport", null, "run_mcp_viewport"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("mock.weather.query", Map.of("city", "Shanghai"), "call_mcp_viewport")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_mcp_viewport",
                context,
                false
        );

        assertThat(singleToolResult(batch, "call_mcp_viewport")).contains("\"source\":\"mcp\"");
    }

    @Test
    void backendIllegalArgumentShouldNotRetry() throws Exception {
        FlakyIllegalArgumentTool badArgsTool = new FlakyIllegalArgumentTool("bad_args_tool");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(badArgsTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                null
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 500L, 3)
        );
        ExecutionContext context = executionContext(
                definition(List.of("bad_args_tool"), budget),
                new AgentRequest("test", "chat_retry_2", null, "run_retry_2"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("bad_args_tool", Map.of(), "call_retry_2")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_2",
                context,
                false
        );

        assertThat(badArgsTool.attempts()).isEqualTo(1);
        String result = singleToolResult(batch, "call_retry_2");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("invalid arguments");
    }

    @Test
    void backendTimeoutShouldRetryByToolBudget() throws Exception {
        SlowTool slowTool = new SlowTool("slow_tool", 80L);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(slowTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                null
        );

        Budget budget = new Budget(
                60_000L,
                new Budget.Scope(10, 60_000L, 0),
                new Budget.Scope(10, 20L, 2)
        );
        ExecutionContext context = executionContext(
                definition(List.of("slow_tool"), budget),
                new AgentRequest("test", "chat_retry_3", null, "run_retry_3"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("slow_tool", Map.of(), "call_retry_3")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_retry_3",
                context,
                false
        );

        assertThat(slowTool.attempts()).isEqualTo(3);
        String result = singleToolResult(batch, "call_retry_3");
        assertThat(result).contains("Backend tool timeout");
        assertThat(result).contains("\"ok\":false");
    }

    @Test
    void backendShouldUseInjectedToolInvoker() {
        ConstantTool localTool = new ConstantTool("mock.weather.query", "LOCAL_RESULT");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(localTool));
        AtomicInteger invocations = new AtomicInteger();
        ToolInvoker toolInvoker = (toolName, args, context) -> {
            invocations.incrementAndGet();
            return objectMapper.createObjectNode()
                    .put("tool", toolName)
                    .put("from", "router")
                    .put("ok", true);
        };

        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                new LoggingAgentProperties(),
                toolInvoker
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall("mock.weather.query", Map.of("city", "shanghai"), "call_mcp_router_1")),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_mcp_router_1",
                executionContext(
                        definition(List.of("mock.weather.query"), Budget.DEFAULT),
                        new AgentRequest("test", "chat_mcp_router_1", null, "run_mcp_router_1"),
                        List.of()
                ),
                false
        );

        assertThat(invocations.get()).isEqualTo(1);
        String result = singleToolResult(batch, "call_mcp_router_1");
        assertThat(result).contains("\"from\":\"router\"");
        assertThat(result).contains("\"ok\":true");
    }

    @Test
    void backendStructuredErrorShouldNotInterruptFollowingCalls() {
        ConstantTool toolA = new ConstantTool("mock.weather.query", "LOCAL_A");
        ConstantTool toolB = new ConstantTool("mock.todo.tasks.list", "LOCAL_B");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(toolA, toolB));
        AtomicInteger invocations = new AtomicInteger();
        ToolInvoker toolInvoker = (toolName, args, context) -> {
            int index = invocations.incrementAndGet();
            if (index == 1) {
                return objectMapper.createObjectNode()
                        .put("tool", toolName)
                        .put("ok", false)
                        .put("code", "mcp_call_failed")
                        .put("error", "mock failure");
            }
            return objectMapper.createObjectNode()
                    .put("tool", toolName)
                    .put("ok", true)
                    .put("result", "done");
        };

        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                new LoggingAgentProperties(),
                toolInvoker
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(
                        new PlannedToolCall("mock.weather.query", Map.of(), "call_mcp_error_1"),
                        new PlannedToolCall("mock.todo.tasks.list", Map.of(), "call_mcp_error_2")
                ),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_mcp_error_1",
                executionContext(
                        definition(List.of("mock.weather.query", "mock.todo.tasks.list"), Budget.DEFAULT),
                        new AgentRequest("test", "chat_mcp_error_1", null, "run_mcp_error_1"),
                        List.of()
                ),
                false
        );

        assertThat(invocations.get()).isEqualTo(2);
        String firstResult = singleToolResult(batch, "call_mcp_error_1");
        String secondResult = singleToolResult(batch, "call_mcp_error_2");
        assertThat(firstResult).contains("\"ok\":false");
        assertThat(firstResult).contains("\"code\":\"mcp_call_failed\"");
        assertThat(secondResult).contains("\"ok\":true");
        assertThat(secondResult).contains("\"result\":\"done\"");
    }

    @Test
    void artifactPublishToolShouldEmitIndependentArtifactPublishEventAfterToolResult() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        ChatDataPathService chatDataPathService = new ChatDataPathService(properties);
        ChatAssetCatalogService chatAssetCatalogService = new ChatAssetCatalogService(chatDataPathService);
        PublishArtifactTool publishArtifactTool = new PublishArtifactTool(
                new ArtifactPublishService(chatDataPathService, chatAssetCatalogService)
        );
        ToolRegistry toolRegistry = new ToolRegistry(List.of(publishArtifactTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                new LocalToolInvoker(toolRegistry)
        );

        Path sourceDir = Path.of("target/test-artifacts");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("publish-artifact-" + System.nanoTime() + ".md");
        Files.writeString(sourceFile, "# report\nhello\n", StandardCharsets.UTF_8);

        String chatId = "123e4567-e89b-12d3-a456-426614174222";
        ExecutionContext context = executionContext(
                definition(List.of("_artifact_publish_"), Budget.DEFAULT),
                new AgentRequest("test", chatId, null, "run_artifact_publish_1"),
                List.of()
        );

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(new PlannedToolCall(
                        "_artifact_publish_",
                        Map.of("path", sourceFile.toAbsolutePath().toString(), "description", "生成的报告"),
                        "call_artifact_publish_1"
                )),
                enabledTools(toolRegistry),
                new ArrayList<>(),
                "run_artifact_publish_1",
                context,
                true
        );

        List<StreamEvent> events = assembleEvents(toolRegistry, chatId, "run_artifact_publish_1", batch.deltas());
        int toolEnd = indexOfEvent(events, "tool.end", "toolId", "call_artifact_publish_1");
        int artifactPublish = indexOfEvent(events, "artifact.publish", "artifactId", null);
        int toolResult = indexOfEvent(events, "tool.result", "toolId", "call_artifact_publish_1");

        assertThat(toolEnd).isGreaterThanOrEqualTo(0);
        assertThat(toolResult).isGreaterThan(toolEnd);
        assertThat(artifactPublish).isGreaterThan(toolResult);

        StreamEvent artifactEvent = events.get(artifactPublish);
        assertThat(artifactEvent.payload()).containsEntry("chatId", chatId);
        assertThat(artifactEvent.payload()).containsEntry("runId", "run_artifact_publish_1");
        assertThat(artifactEvent.payload()).doesNotContainKey("source");

        ArtifactEventPayload artifact = (ArtifactEventPayload) artifactEvent.payload().get("artifact");
        assertThat(artifact.url()).contains("/api/resource?file=");
        String result = singleToolResult(batch, "call_artifact_publish_1");
        assertThat(result).contains("\"artifactId\"");
        assertThat(result).contains("\"ok\":true");

        Path publishedDir = tempDir.resolve("chats").resolve(chatId).resolve("artifacts").resolve("run_artifact_publish_1");
        assertThat(Files.list(publishedDir).toList()).hasSize(1);
    }

    @Test
    void unregisteredToolShouldReturnStandardErrorCodeAndStopFollowingCalls() throws Exception {
        ConstantTool registeredTool = new ConstantTool("registered_tool", "LOCAL_OK");
        ToolRegistry toolRegistry = new ToolRegistry(List.of(registeredTool));
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                new ToolArgumentResolver(objectMapper),
                objectMapper,
                null,
                null,
                null
        );

        Map<String, BaseTool> stageTools = new LinkedHashMap<>();
        stageTools.put("missing_tool", new ConstantTool("missing_tool", "IGNORED"));
        stageTools.put("registered_tool", registeredTool);

        ToolExecutionService.ToolExecutionBatch batch = toolExecutionService.executeToolCalls(
                List.of(
                        new PlannedToolCall("missing_tool", Map.of(), "call_missing_1"),
                        new PlannedToolCall("registered_tool", Map.of(), "call_registered_1")
                ),
                Map.copyOf(stageTools),
                new ArrayList<>(),
                "run_missing_tool_1",
                executionContext(
                        definition(List.of("missing_tool", "registered_tool"), Budget.DEFAULT),
                        new AgentRequest("test", "chat_missing_tool_1", null, "run_missing_tool_1"),
                        List.of()
                ),
                false
        );

        String firstResult = singleToolResult(batch, "call_missing_1");
        com.fasterxml.jackson.databind.JsonNode resultNode = objectMapper.readTree(firstResult);
        assertThat(resultNode.path("ok").asBoolean(true)).isFalse();
        assertThat(resultNode.path("code").asText()).isEqualTo(ToolExecutionService.TOOL_NOT_REGISTERED_CODE);
        assertThat(resultNode.path("error").asText()).contains("Tool is not registered");
        assertThat(batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .map(AgentDelta.ToolResult::toolId)
                .toList()).containsExactly("call_missing_1");
    }

    private Map<String, BaseTool> enabledTools(ToolRegistry toolRegistry) {
        Map<String, BaseTool> enabledTools = new LinkedHashMap<>();
        for (BaseTool tool : toolRegistry.list()) {
            enabledTools.put(tool.name(), tool);
        }
        return Map.copyOf(enabledTools);
    }

    private String singleToolResult(ToolExecutionService.ToolExecutionBatch batch, String callId) {
        return batch.deltas().stream()
                .flatMap(delta -> delta.toolResults().stream())
                .filter(item -> callId.equals(item.toolId()))
                .map(AgentDelta.ToolResult::result)
                .findFirst()
                .orElseThrow();
    }

    private AgentDefinition definition() {
        return definition(List.of("_plan_get_tasks_"), Budget.DEFAULT);
    }

    private AgentDefinition definition(List<String> tools, Budget budget) {
        return new AgentDefinition(
                "runtime_test",
                "runtime_test",
                null,
                "runtime test",
                "bailian",
                "qwen3-max",
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, budget),
                new OneshotMode(new StageSettings("sys", null, null, tools, false, ComputePolicy.MEDIUM), null, null),
                tools,
                List.of()
        );
    }

    private ExecutionContext executionContext(
            AgentDefinition definition,
            AgentRequest request,
            List<ChatMessage> historyMessages
    ) {
        return ExecutionContext.builder(definition, request)
                .historyMessages(historyMessages)
                .build();
    }

    private List<StreamEvent> assembleEvents(
            ToolRegistry toolRegistry,
            String chatId,
            String runId,
            List<AgentDelta> deltas
    ) {
        AgentDeltaToStreamInputMapper mapper = new AgentDeltaToStreamInputMapper(runId, chatId, toolRegistry, null);
        StreamEventAssembler.EventStreamState state = new StreamEventAssembler()
                .begin(new StreamRequest.Query(
                        "req_1",
                        chatId,
                        "user",
                        "publish artifact",
                        "runtime_test",
                        null,
                        null,
                        null,
                        null,
                        true,
                        chatId,
                        runId
                ));
        List<StreamEvent> events = new ArrayList<>(state.bootstrapEvents());
        for (AgentDelta delta : deltas) {
            for (StreamEnvelope envelope : mapper.mapEnvelopesOrEmpty(delta)) {
                events.addAll(state.consume(envelope));
            }
        }
        events.addAll(state.complete());
        return events;
    }

    private int indexOfEvent(List<StreamEvent> events, String type, String payloadKey, String payloadValue) {
        for (int i = 0; i < events.size(); i++) {
            StreamEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            if (payloadKey == null) {
                return i;
            }
            Object value = event.payload().get(payloadKey);
            if (payloadValue == null) {
                if (value != null) {
                    return i;
                }
                continue;
            }
            if (payloadValue.equals(String.valueOf(value))) {
                return i;
            }
        }
        return -1;
    }

    private ToolDescriptor frontendDescriptor(String name) {
        return new ToolDescriptor(
                name,
                name,
                "",
                null,
                null,
                false,
                true,
                false,
                "html",
                "local",
                null,
                name,
                "test://frontend"
        );
    }

    private ToolDescriptor mcpViewportDescriptor(String name, String viewportKey) {
        return new ToolDescriptor(
                name,
                name,
                "",
                null,
                null,
                false,
                true,
                false,
                "html",
                "mcp",
                "mock",
                viewportKey,
                "test://mcp"
        );
    }

    private static final class ConstantTool implements BaseTool, ToolMetadataAware {
        private final String name;
        private final String value;
        private final ToolDescriptor descriptor;

        private ConstantTool(String name, String value) {
            this(name, value, null);
        }

        private ConstantTool(String name, String value, ToolDescriptor descriptor) {
            this.name = name;
            this.value = value;
            this.descriptor = descriptor;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(value);
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }
    }

    private static final class FlakyTool implements BaseTool {
        private final String name;
        private final int failuresBeforeSuccess;
        private final String successValue;
        private final AtomicInteger attempts = new AtomicInteger();

        private FlakyTool(String name, int failuresBeforeSuccess, String successValue) {
            this.name = name;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.successValue = successValue;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            int current = attempts.incrementAndGet();
            if (current <= failuresBeforeSuccess) {
                throw new RuntimeException("temporary failure " + current);
            }
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(successValue);
        }

        private int attempts() {
            return attempts.get();
        }
    }

    private static final class FlakyIllegalArgumentTool implements BaseTool {
        private final String name;
        private final AtomicInteger attempts = new AtomicInteger();

        private FlakyIllegalArgumentTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("invalid arguments");
        }

        private int attempts() {
            return attempts.get();
        }
    }

    private static final class SlowTool implements BaseTool {
        private final String name;
        private final long sleepMs;
        private final AtomicInteger attempts = new AtomicInteger();

        private SlowTool(String name, long sleepMs) {
            this.name = name;
            this.sleepMs = sleepMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode invoke(Map<String, Object> args) {
            attempts.incrementAndGet();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                // cancelled by timeout policy, ignore and let this invocation end
            }
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("slow-ok");
        }

        private int attempts() {
            return attempts.get();
        }
    }
}
