package com.linlay.agentplatform.chat.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.ChatStorageProperties;
import com.linlay.agentplatform.chat.storage.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStorageStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPersistQueryAndStepLinesWithV3Schema() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);
        properties.setActionTools(List.of("switch_frontend_theme"));

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174000";
        String runId = "run_001";

        store.appendQueryLine(chatId, runId, query(runId, chatId, "请帮我切换主题"));

        store.appendStepLine(
                chatId,
                runId,
                "oneshot",
                1,
                null,
                systemSnapshot("gpt-5.2", "你是一个有用的助手", true),
                null,
                null,
                List.of(
                        ChatStorageTypes.RunMessage.user("请帮我切换主题", 1770945237570L),
                        ChatStorageTypes.RunMessage.assistantReasoning("准备调用前端动作", 1770945237571L, 10L, null),
                        ChatStorageTypes.RunMessage.assistantToolCall(
                                "switch_frontend_theme",
                                "call_tool_1",
                                "{\"theme\":\"dark\"}",
                                1770945237572L,
                                50L,
                                null
                        ),
                        ChatStorageTypes.RunMessage.toolResult(
                                "switch_frontend_theme",
                                "call_tool_1",
                                "OK",
                                1770945237573L,
                                5L
                        ),
                        ChatStorageTypes.RunMessage.assistantContent("已切换到 dark 主题", 1770945237574L, 20L, null)
                )
        );

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        assertThat(Files.exists(file)).isTrue();

        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(2);

        // First line is query
        JsonNode queryLine = objectMapper.readTree(lines.get(0));
        assertThat(queryLine.path("_type").asText()).isEqualTo("query");
        assertThat(queryLine.path("chatId").asText()).isEqualTo(chatId);
        assertThat(queryLine.path("runId").asText()).isEqualTo(runId);
        assertThat(queryLine.path("query").path("message").asText()).isEqualTo("请帮我切换主题");

        // Second line is step
        JsonNode stepLine = objectMapper.readTree(lines.get(1));
        assertThat(stepLine.path("_type").asText()).isEqualTo("step");
        assertThat(stepLine.path("chatId").asText()).isEqualTo(chatId);
        assertThat(stepLine.path("runId").asText()).isEqualTo(runId);
        assertThat(stepLine.path("_stage").asText()).isEqualTo("oneshot");
        assertThat(stepLine.path("_seq").asInt()).isEqualTo(1);
        assertThat(stepLine.path("system").path("model").asText()).isEqualTo("gpt-5.2");

        JsonNode assistantReasoning = stepLine.path("messages").get(1);
        assertThat(assistantReasoning.path("role").asText()).isEqualTo("assistant");
        assertThat(assistantReasoning.has("reasoning_content")).isTrue();
        assertThat(assistantReasoning.has("content")).isFalse();
        assertThat(assistantReasoning.path("_reasoningId").asText()).startsWith(runId + "_r_");

        JsonNode assistantToolCall = stepLine.path("messages").get(2);
        assertThat(assistantToolCall.path("role").asText()).isEqualTo("assistant");
        assertThat(assistantToolCall.path("tool_calls")).hasSize(1);
        assertThat(assistantToolCall.path("_actionId").asText()).isEqualTo("call_tool_1");
        assertThat(assistantToolCall.has("_toolId")).isFalse();

        JsonNode toolResult = stepLine.path("messages").get(3);
        assertThat(toolResult.path("role").asText()).isEqualTo("tool");
        assertThat(toolResult.path("_actionId").asText()).isEqualTo("call_tool_1");

        // Load history messages: reasoning excluded, others included
        List<ChatMessage> historyMessages = store.loadHistoryMessages(chatId);
        assertThat(historyMessages).hasSize(4);
        assertThat(historyMessages.get(0)).isInstanceOf(ChatMessage.UserMsg.class);
        assertThat(historyMessages.get(1)).isInstanceOf(ChatMessage.AssistantMsg.class);
        assertThat(historyMessages.get(2)).isInstanceOf(ChatMessage.ToolResultMsg.class);
        assertThat(historyMessages.get(3)).isInstanceOf(ChatMessage.AssistantMsg.class);
    }

    @Test
    void shouldPersistHiddenOnQueryLineTopLevelOnly() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174008";
        String runId = "run_hidden_001";

        Map<String, Object> query = query(runId, chatId, "隐藏输入");
        query.put("hidden", true);
        store.appendQueryLine(chatId, runId, query);

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        JsonNode queryLine = objectMapper.readTree(lines.getFirst());

        assertThat(queryLine.path("_type").asText()).isEqualTo("query");
        assertThat(queryLine.path("hidden").asBoolean()).isTrue();
        assertThat(queryLine.path("query").path("message").asText()).isEqualTo("隐藏输入");
        assertThat(queryLine.path("query").has("hidden")).isFalse();
    }

    @Test
    void shouldPersistActionIdWhenToolCallTypeIsFunctionButSystemDeclaresActionTool() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174005";
        String runId = "run_005";
        String callId = "call_f6bdbca0d6c041b7ae720fd6";

        store.appendQueryLine(chatId, runId, query(runId, chatId, "放烟花"));
        store.appendStepLine(
                chatId,
                runId,
                "oneshot",
                1,
                null,
                systemSnapshotWithTools("qwen3-max", "你是 UI 动作助手。", true, List.of(
                        toolSnapshot("action", "launch_fireworks"),
                        toolSnapshot("action", "show_modal"),
                        toolSnapshot("action", "switch_theme")
                )),
                null,
                null,
                List.of(
                        ChatStorageTypes.RunMessage.user("放烟花", 1772438369610L),
                        ChatStorageTypes.RunMessage.assistantToolCall(
                                "launch_fireworks",
                                callId,
                                "function",
                                "{\"durationMs\":5000}",
                                1772438367295L,
                                366L,
                                null
                        ),
                        ChatStorageTypes.RunMessage.toolResult("launch_fireworks", callId, "OK", 1772438367295L, 366L),
                        ChatStorageTypes.RunMessage.assistantContent("烟花已成功绽放！", 1772438369380L, 230L, Map.of("total_tokens", 529))
                )
        );

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        JsonNode stepLine = objectMapper.readTree(lines.get(1));

        JsonNode assistantToolCall = stepLine.path("messages").get(1);
        assertThat(assistantToolCall.path("_actionId").asText()).isEqualTo(callId);
        assertThat(assistantToolCall.has("_toolId")).isFalse();

        JsonNode toolResult = stepLine.path("messages").get(2);
        assertThat(toolResult.path("_actionId").asText()).isEqualTo(callId);
        assertThat(toolResult.has("_toolId")).isFalse();
    }

    @Test
    void shouldPreferExplicitActionTypeOverSystemToolDeclarations() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);
        String chatId = "123e4567-e89b-12d3-a456-426614174006";
        String callId = "call_action_explicit_1";

        store.appendQueryLine(chatId, "run_006", query("run_006", chatId, "执行动作"));
        store.appendStepLine(
                chatId,
                "run_006",
                "oneshot",
                1,
                null,
                systemSnapshotWithTools("qwen3-max", "sys", true, List.of(
                        toolSnapshot("function", "launch_fireworks")
                )),
                null,
                null,
                List.of(
                        ChatStorageTypes.RunMessage.user("执行动作", 1000L),
                        ChatStorageTypes.RunMessage.assistantToolCall(
                                "launch_fireworks",
                                callId,
                                "action",
                                "{\"durationMs\":3000}",
                                1001L,
                                20L,
                                null
                        ),
                        ChatStorageTypes.RunMessage.toolResult("launch_fireworks", callId, "OK", 1002L, 10L)
                )
        );

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        JsonNode stepLine = objectMapper.readTree(lines.get(1));
        JsonNode assistantToolCall = stepLine.path("messages").get(1);
        JsonNode toolResult = stepLine.path("messages").get(2);

        assertThat(assistantToolCall.path("_actionId").asText()).isEqualTo(callId);
        assertThat(toolResult.path("_actionId").asText()).isEqualTo(callId);
        assertThat(assistantToolCall.has("_toolId")).isFalse();
        assertThat(toolResult.has("_toolId")).isFalse();
    }

    @Test
    void shouldTrimToConfiguredWindowSizeByRunId() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(2);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174001";

        // Run 1
        store.appendQueryLine(chatId, "run_001", query("run_001", chatId, "u1"));
        store.appendStepLine(chatId, "run_001", "oneshot", 1, null,
                systemSnapshot("gpt-5.2", "system", true), null, null,
                List.of(
                        ChatStorageTypes.RunMessage.user("u1", 1000L),
                        ChatStorageTypes.RunMessage.assistantReasoning("r1", 1001L, 1L, null),
                        ChatStorageTypes.RunMessage.assistantContent("a1", 1002L, 2L, null)
                ));

        // Run 2
        store.appendQueryLine(chatId, "run_002", query("run_002", chatId, "u2"));
        store.appendStepLine(chatId, "run_002", "oneshot", 1, null, null, null, null,
                List.of(
                        ChatStorageTypes.RunMessage.user("u2", 2000L),
                        ChatStorageTypes.RunMessage.assistantContent("a2", 2001L, 2L, null)
                ));

        // Run 3
        store.appendQueryLine(chatId, "run_003", query("run_003", chatId, "u3"));
        store.appendStepLine(chatId, "run_003", "oneshot", 1, null, null, null, null,
                List.of(
                        ChatStorageTypes.RunMessage.user("u3", 3000L),
                        ChatStorageTypes.RunMessage.assistantContent("a3", 3001L, 2L, null)
                ));

        // Trim
        store.trimToWindow(chatId);

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        // k=2 means keep 2 runs: run_002 (query+step) and run_003 (query+step) = 4 lines
        assertThat(lines).hasSize(4);
        assertThat(objectMapper.readTree(lines.get(0)).path("runId").asText()).isEqualTo("run_002");
        assertThat(objectMapper.readTree(lines.get(1)).path("runId").asText()).isEqualTo("run_002");
        assertThat(objectMapper.readTree(lines.get(2)).path("runId").asText()).isEqualTo("run_003");
        assertThat(objectMapper.readTree(lines.get(3)).path("runId").asText()).isEqualTo("run_003");

        // Load history: run_002 + run_003 messages (reasoning excluded)
        List<ChatMessage> historyMessages = store.loadHistoryMessages(chatId);
        assertThat(historyMessages).hasSize(4);
        assertThat(((ChatMessage.UserMsg) historyMessages.get(0)).text()).isEqualTo("u2");
        assertThat(((ChatMessage.AssistantMsg) historyMessages.get(1)).text()).isEqualTo("a2");
        assertThat(((ChatMessage.UserMsg) historyMessages.get(2)).text()).isEqualTo("u3");
        assertThat(((ChatMessage.AssistantMsg) historyMessages.get(3)).text()).isEqualTo("a3");
    }

    @Test
    void shouldPersistSystemOnlyWhenProvided() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        String chatId = "123e4567-e89b-12d3-a456-426614174002";

        // Step 1: with system
        store.appendQueryLine(chatId, "run_001", query("run_001", chatId, "第一轮"));
        store.appendStepLine(chatId, "run_001", "react", 1, null,
                systemSnapshot("gpt-5.2", "你是一个有用的助手", true), null, null,
                List.of(
                        ChatStorageTypes.RunMessage.user("第一轮", 1000L),
                        ChatStorageTypes.RunMessage.assistantContent("ok", 1001L, 1L, null)
                ));

        // Step 2: without system (null)
        store.appendStepLine(chatId, "run_001", "react", 2, null, null, null, null,
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("ok2", 2001L, 1L, null)
                ));

        // Step 3: with different system
        store.appendStepLine(chatId, "run_001", "react", 3, null,
                systemSnapshot("gpt-5.2", "你是另一个系统提示", true), null, null,
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("ok3", 3001L, 1L, null)
                ));

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(4); // 1 query + 3 steps

        JsonNode first = objectMapper.readTree(lines.get(1));
        JsonNode second = objectMapper.readTree(lines.get(2));
        JsonNode third = objectMapper.readTree(lines.get(3));

        assertThat(first.has("system")).isTrue();
        assertThat(second.has("system")).isFalse();
        assertThat(third.has("system")).isTrue();
        assertThat(third.path("system").path("messages").get(0).path("content").asText()).isEqualTo("你是另一个系统提示");

        // loadLatestSystemSnapshot returns the last one
        ChatStorageTypes.SystemSnapshot latest = store.loadLatestSystemSnapshot(chatId);
        assertThat(latest).isNotNull();
        assertThat(latest.messages.getFirst().content).isEqualTo("你是另一个系统提示");
    }

    @Test
    void shouldPersistPlanStateOnStepLineAndLoadLatest() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);
        String chatId = "123e4567-e89b-12d3-a456-426614174003";

        // Plan step with initial plan
        store.appendQueryLine(chatId, "run_001", query("run_001", chatId, "第一轮"));
        store.appendStepLine(chatId, "run_001", "plan", 1, null,
                systemSnapshot("qwen3-max", "system", true),
                planState("plan_chat_001", List.of(
                        task("task0", "收集信息", "init"),
                        task("task1", "执行任务", "init")
                )),
                null,
                List.of(
                        ChatStorageTypes.RunMessage.user("第一轮", 1000L),
                        ChatStorageTypes.RunMessage.assistantContent("ok", 1001L, 1L, null)
                ));

        // Execute step with updated plan
        store.appendStepLine(chatId, "run_001", "execute", 2, "task0", null,
                planState("plan_chat_001", List.of(
                        task("task0", "收集信息", "completed"),
                        task("task1", "执行任务", "init")
                )),
                null,
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("done task0", 2001L, 1L, null)
                ));

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(3); // 1 query + 2 steps
        assertThat(lines.get(1)).contains("\"plan\":");
        assertThat(lines.get(2)).contains("\"plan\":");

        // Check execute step has taskId
        JsonNode executeStep = objectMapper.readTree(lines.get(2));
        assertThat(executeStep.path("taskId").asText()).isEqualTo("task0");
        assertThat(executeStep.path("_stage").asText()).isEqualTo("execute");
        assertThat(executeStep.path("_seq").asInt()).isEqualTo(2);

        ChatStorageTypes.PlanState latest = store.loadLatestPlanState(chatId);
        assertThat(latest).isNotNull();
        assertThat(latest.planId).isEqualTo("plan_chat_001");
        assertThat(latest.tasks).hasSize(2);
        assertThat(latest.tasks.get(0).status).isEqualTo("completed");
        assertThat(latest.tasks.get(1).status).isEqualTo("init");
    }

    @Test
    void shouldPersistArtifactStateOnStepLineAndLoadLatest() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);
        String chatId = "123e4567-e89b-12d3-a456-426614174013";

        store.appendQueryLine(chatId, "run_001", query("run_001", chatId, "生成文件"));
        store.appendStepLine(chatId, "run_001", "execute", 1, "task0",
                null,
                null,
                artifactState(List.of(
                        artifactItem("asset_1", "file", "report.md", "text/markdown", 12L,
                                "/api/resource?file=" + chatId + "%2Fartifacts%2Frun_001%2Freport.md", "sha-1")
                )),
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("report ready", 1000L, 1L, null)
                ));
        store.appendStepLine(chatId, "run_001", "summary", 2, null,
                null,
                null,
                artifactState(List.of(
                        artifactItem("asset_1", "file", "report.md", "text/markdown", 12L,
                                "/api/resource?file=" + chatId + "%2Fartifacts%2Frun_001%2Freport.md", "sha-1"),
                        artifactItem("asset_2", "file", "outline.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 24L,
                                "/api/resource?file=" + chatId + "%2Foutline.docx", "sha-2")
                )),
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("all done", 1001L, 1L, null)
                ));

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).contains("\"artifacts\":");
        assertThat(lines.get(2)).contains("\"artifacts\":");

        ChatStorageTypes.ArtifactState latest = store.loadLatestArtifactState(chatId);
        assertThat(latest).isNotNull();
        assertThat(latest.items).hasSize(2);
        assertThat(latest.items.get(0).artifactId).isEqualTo("asset_1");
        assertThat(latest.items.get(1).artifactId).isEqualTo("asset_2");
    }

    @Test
    void shouldLoadHistoryFromMultiStepReactRun() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);
        String chatId = "123e4567-e89b-12d3-a456-426614174004";

        store.appendQueryLine(chatId, "run_001", query("run_001", chatId, "help"));

        // React step 1
        store.appendStepLine(chatId, "run_001", "react", 1, null,
                systemSnapshot("qwen3-max", "sys", true), null, null,
                List.of(
                        ChatStorageTypes.RunMessage.user("help", 1000L),
                        ChatStorageTypes.RunMessage.assistantToolCall("_bash_", "call_1", "{\"cmd\":\"ls\"}", 1001L, 10L, null),
                        ChatStorageTypes.RunMessage.toolResult("_bash_", "call_1", "file.txt", 1002L, 5L)
                ));

        // React step 2
        store.appendStepLine(chatId, "run_001", "react", 2, null, null, null, null,
                List.of(
                        ChatStorageTypes.RunMessage.assistantContent("found file.txt", 2000L, 20L, null)
                ));

        List<ChatMessage> messages = store.loadHistoryMessages(chatId);
        // user, assistantToolCall, toolResult, assistantContent
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(ChatMessage.UserMsg.class);
        assertThat(messages.get(1)).isInstanceOf(ChatMessage.AssistantMsg.class);
        assertThat(messages.get(2)).isInstanceOf(ChatMessage.ToolResultMsg.class);
        assertThat(messages.get(3)).isInstanceOf(ChatMessage.AssistantMsg.class);
        assertThat(((ChatMessage.AssistantMsg) messages.get(3)).text()).isEqualTo("found file.txt");
    }

    @Test
    void shouldContinueFallbackContentAndReasoningIdsAcrossStepsWithinRun() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);
        String chatId = "123e4567-e89b-12d3-a456-426614174007";
        String runId = "run_010";

        store.appendQueryLine(chatId, runId, query(runId, chatId, "连续 step"));
        store.appendStepLine(chatId, runId, "execute", 1, "task_1", null, null, null, List.of(
                ChatStorageTypes.RunMessage.user("连续 step", 1000L),
                ChatStorageTypes.RunMessage.assistantReasoning("r1", 1001L, 5L, null),
                ChatStorageTypes.RunMessage.assistantContent("c1", 1002L, 5L, null)
        ));
        store.appendStepLine(chatId, runId, "execute", 2, "task_2", null, null, null, List.of(
                ChatStorageTypes.RunMessage.assistantContent("c2", 2000L, 5L, null)
        ));
        store.appendStepLine(chatId, runId, "summary", 3, null, null, null, null, List.of(
                ChatStorageTypes.RunMessage.assistantReasoning("r2", 3000L, 5L, null),
                ChatStorageTypes.RunMessage.assistantContent("c3", 3001L, 5L, null)
        ));

        Path file = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank()).toList();
        JsonNode step1 = objectMapper.readTree(lines.get(1));
        JsonNode step2 = objectMapper.readTree(lines.get(2));
        JsonNode step3 = objectMapper.readTree(lines.get(3));

        assertThat(step1.path("messages").get(1).path("_reasoningId").asText()).isEqualTo("run_010_r_1");
        assertThat(step1.path("messages").get(2).path("_contentId").asText()).isEqualTo("run_010_c_1");
        assertThat(step2.path("messages").get(0).path("_contentId").asText()).isEqualTo("run_010_c_2");
        assertThat(step3.path("messages").get(0).path("_reasoningId").asText()).isEqualTo("run_010_r_2");
        assertThat(step3.path("messages").get(1).path("_contentId").asText()).isEqualTo("run_010_c_3");
    }

    @Test
    void isSameSystemShouldCompareByJsonEquality() throws Exception {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.setK(20);

        ChatStorageStore store = new ChatStorageStore(objectMapper, properties);

        ChatStorageTypes.SystemSnapshot a = systemSnapshot("gpt-5.2", "prompt", true);
        ChatStorageTypes.SystemSnapshot b = systemSnapshot("gpt-5.2", "prompt", true);
        ChatStorageTypes.SystemSnapshot c = systemSnapshot("gpt-5.2", "different", true);

        assertThat(store.isSameSystem(a, b)).isTrue();
        assertThat(store.isSameSystem(a, c)).isFalse();
        assertThat(store.isSameSystem(null, b)).isFalse();
    }

    private Map<String, Object> query(String requestId, String chatId, String message) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("requestId", requestId);
        query.put("chatId", chatId);
        query.put("role", "user");
        query.put("message", message);
        query.put("references", List.of());
        query.put("params", Map.of("x", 1));
        query.put("scene", Map.of("url", "https://example.com", "title", "demo"));
        query.put("stream", true);
        return query;
    }

    private ChatStorageTypes.SystemSnapshot systemSnapshot(String model, String prompt, boolean stream) {
        return systemSnapshotWithTools(model, prompt, stream, List.of(
                toolSnapshot("function", "mock.sensitive-data.detect")
        ));
    }

    private ChatStorageTypes.SystemSnapshot systemSnapshotWithTools(
            String model,
            String prompt,
            boolean stream,
            List<ChatStorageTypes.SystemToolSnapshot> tools
    ) {
        ChatStorageTypes.SystemSnapshot snapshot = new ChatStorageTypes.SystemSnapshot();
        snapshot.model = model;
        snapshot.stream = stream;

        ChatStorageTypes.SystemMessageSnapshot systemMessage = new ChatStorageTypes.SystemMessageSnapshot();
        systemMessage.role = "system";
        systemMessage.content = prompt;
        snapshot.messages = List.of(systemMessage);
        snapshot.tools = tools;
        return snapshot;
    }

    private ChatStorageTypes.SystemToolSnapshot toolSnapshot(String type, String name) {
        ChatStorageTypes.SystemToolSnapshot tool = new ChatStorageTypes.SystemToolSnapshot();
        tool.type = type;
        tool.name = name;
        tool.description = "检测敏感信息";
        tool.parameters = Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);
        return tool;
    }

    private ChatStorageTypes.PlanState planState(
            String planId,
            List<ChatStorageTypes.PlanTaskState> tasks
    ) {
        ChatStorageTypes.PlanState state = new ChatStorageTypes.PlanState();
        state.planId = planId;
        state.tasks = tasks;
        return state;
    }

    private ChatStorageTypes.ArtifactState artifactState(List<ChatStorageTypes.ArtifactItemState> items) {
        ChatStorageTypes.ArtifactState state = new ChatStorageTypes.ArtifactState();
        state.items = items;
        return state;
    }

    private ChatStorageTypes.ArtifactItemState artifactItem(
            String artifactId,
            String type,
            String name,
            String mimeType,
            Long sizeBytes,
            String url,
            String sha256
    ) {
        ChatStorageTypes.ArtifactItemState item = new ChatStorageTypes.ArtifactItemState();
        item.artifactId = artifactId;
        item.type = type;
        item.name = name;
        item.mimeType = mimeType;
        item.sizeBytes = sizeBytes;
        item.url = url;
        item.sha256 = sha256;
        return item;
    }

    private ChatStorageTypes.PlanTaskState task(String taskId, String description, String status) {
        ChatStorageTypes.PlanTaskState task = new ChatStorageTypes.PlanTaskState();
        task.taskId = taskId;
        task.description = description;
        task.status = status;
        return task;
    }
}
