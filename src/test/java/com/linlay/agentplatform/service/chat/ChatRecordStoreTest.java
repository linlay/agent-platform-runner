package com.linlay.agentplatform.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRecordStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadChatShouldReturnEventsOnlyWhenIncludeRawMessagesIsFalse() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174010";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "测试会话", 1707000000000L, 1707000000000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_001", query("run_001", chatId, "你好", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_001", "oneshot", 1, null,
                1707000000000L,
                List.of(
                        userMessage("你好", 1707000000000L),
                        assistantContentMessage("你好，我是助手", 1707000000001L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);

        assertThat(detail.chatId()).isEqualTo(chatId);
        assertThat(detail.chatName()).isEqualTo("测试会话");
        assertThat(detail.rawMessages()).isNull();
        assertThat(detail.events()).isNotNull();
        assertThat(detail.events()).extracting(item -> item.get("type"))
                .contains(
                        "request.query",
                        "chat.start",
                        "run.start",
                        "content.snapshot",
                        "run.complete"
                );
        Map<String, Object> runStart = detail.events().stream()
                .filter(event -> "run.start".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(runStart).containsEntry("agentKey", "demo");
        assertThat(detail.events().getFirst()).containsKey("seq");
        assertThat(detail.references()).isNull();
    }

    @Test
    void loadChatShouldFailFastWhenRunQueryMissingAgentKey() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174023";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "兼容会话", 1707000800000L, 1707000800000L);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + chatDir.resolve("chats.db"));
             java.sql.PreparedStatement statement = connection.prepareStatement("UPDATE CHATS SET AGENT_KEY_ = '' WHERE CHAT_ID_ = ?")) {
            statement.setString(1, chatId);
            statement.executeUpdate();
        }

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        Map<String, Object> queryWithoutAgentKey = new LinkedHashMap<>(query("run_compat_1", chatId, "你好", List.of()));
        queryWithoutAgentKey.remove("agentKey");
        writeJsonLine(historyPath, queryLine(chatId, "run_compat_1", queryWithoutAgentKey));
        writeJsonLine(historyPath, stepLine(chatId, "run_compat_1", "oneshot", 1, null,
                1707000800000L,
                List.of(
                        userMessage("你好", 1707000800000L),
                        assistantContentMessage("你好，我是助手", 1707000800001L)
                )));

        ChatRecordStore store = newStore();
        assertThatThrownBy(() -> store.loadChat(chatId, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run.start requires non-blank agentKey in history query")
                .hasMessageContaining("chatId=" + chatId)
                .hasMessageContaining("runId=run_compat_1");
    }

    @Test
    void loadChatShouldFallbackToBoundAgentKeyWhenQueryAgentKeyMissing() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174024";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "兼容会话", 1707000800000L, 1707000800000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        Map<String, Object> queryWithoutAgentKey = new LinkedHashMap<>(query("run_compat_2", chatId, "你好", List.of()));
        queryWithoutAgentKey.remove("agentKey");
        writeJsonLine(historyPath, queryLine(chatId, "run_compat_2", queryWithoutAgentKey));
        writeJsonLine(historyPath, stepLine(chatId, "run_compat_2", "oneshot", 1, null,
                1707000800000L,
                List.of(
                        userMessage("你好", 1707000800000L),
                        assistantContentMessage("你好，我是助手", 1707000800001L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> runStart = detail.events().stream()
                .filter(event -> "run.start".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(runStart).containsEntry("agentKey", "demo");
    }

    @Test
    void loadChatShouldReturnRawMessagesAndSnapshotEventsWhenIncludeRawMessagesIsTrue() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174011";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "快照会话", 1707000100000L, 1707000100000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_002", query("run_002", chatId, "帮我列目录", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_002", "react", 1, null,
                1707000100000L,
                List.of(
                        userMessage("帮我列目录", 1707000100000L),
                        assistantReasoningMessage("先判断是否需要工具", 1707000100001L),
                        assistantToolCallMessage("bash", "call_tool_1", "{\"command\":\"ls\"}", 1707000100002L, "tool_short_1", null),
                        toolMessage("bash", "call_tool_1", "{\"ok\":true}", 1707000100003L, "tool_short_1", null)
                )));
        writeJsonLine(historyPath, stepLine(chatId, "run_002", "react", 2, null,
                1707000100004L,
                List.of(
                        assistantToolCallMessage("switch_frontend_theme", "call_tool_2", "{\"theme\":\"dark\"}", 1707000100004L, null, "action_short_1"),
                        toolMessage("switch_frontend_theme", "call_tool_2", "OK", 1707000100005L, null, "action_short_1"),
                        assistantContentMessage("处理完成", 1707000100006L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, true);

        assertThat(detail.rawMessages()).isNotNull();
        assertThat(detail.rawMessages()).hasSize(7);
        assertThat(detail.rawMessages().getFirst().get("role")).isEqualTo("user");

        assertThat(detail.events()).isNotNull();
        assertThat(detail.events()).extracting(item -> item.get("type")).contains(
                "request.query",
                "chat.start",
                "run.start",
                "reasoning.snapshot",
                "tool.snapshot",
                "tool.result",
                "action.snapshot",
                "action.result",
                "content.snapshot",
                "run.complete"
        );

        assertThat(countType(detail.events(), "tool.start")).isEqualTo(0);
        assertThat(countType(detail.events(), "tool.args")).isEqualTo(0);
        assertThat(countType(detail.events(), "tool.end")).isEqualTo(0);
        assertThat(countType(detail.events(), "action.start")).isEqualTo(0);
        assertThat(countType(detail.events(), "action.args")).isEqualTo(0);
        assertThat(countType(detail.events(), "action.end")).isEqualTo(0);
    }

    @Test
    void loadChatShouldOmitHiddenRequestQueryButKeepOtherReplayData() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174012";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "隐藏 query 会话", 1707000200000L, 1707000200000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        List<Map<String, Object>> references = List.of(Map.of(
                "id", "ref_hidden_001",
                "type", "url",
                "name", "doc",
                "url", "https://example.com/hidden"
        ));
        writeJsonLine(historyPath, queryLine(chatId, "run_hidden_001", query("run_hidden_001", chatId, "隐藏输入", references), true));
        writeJsonLine(historyPath, stepLine(chatId, "run_hidden_001", "oneshot", 1, null,
                1707000200000L,
                List.of(
                        userMessage("隐藏输入", 1707000200000L),
                        assistantContentMessage("已处理", 1707000200001L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, true);

        assertThat(detail.events()).extracting(event -> event.get("type"))
                .doesNotContain("request.query")
                .contains("chat.start", "run.start", "content.snapshot", "run.complete");
        assertThat(detail.rawMessages()).isNotNull();
        assertThat(detail.rawMessages().getFirst()).containsEntry("role", "user");
        assertThat(detail.references()).isNotNull();
        assertThat(detail.references()).extracting(QueryRequest.Reference::id).containsExactly("ref_hidden_001");
    }

    @Test
    void loadChatShouldHideToolSnapshotAndResultWhenToolIsClientInvisible() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174099";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "隐藏工具会话", 1707000100000L, 1707000100000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_099", query("run_099", chatId, "创建计划", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_099", "react", 1, null,
                1707000100000L,
                List.of(
                        userMessage("创建计划", 1707000100000L),
                        assistantToolCallMessage("_plan_add_tasks_", "call_hidden_1", "{\"tasks\":[{\"description\":\"a\"}]}", 1707000100001L, "tool_hidden_1", null),
                        toolMessage("_plan_add_tasks_", "call_hidden_1", "{\"ok\":true}", 1707000100002L, "tool_hidden_1", null),
                        assistantToolCallMessage("bash", "call_visible_1", "{\"command\":\"pwd\"}", 1707000100003L, "tool_visible_1", null),
                        toolMessage("bash", "call_visible_1", "{\"ok\":true}", 1707000100004L, "tool_visible_1", null),
                        assistantContentMessage("完成", 1707000100005L)
                )));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("_plan_add_tasks_")).thenReturn(Optional.of(descriptor("_plan_add_tasks_", false)));
        when(toolRegistry.descriptor("bash")).thenReturn(Optional.of(descriptor("bash", true)));
        when(toolRegistry.label("bash")).thenReturn("bash label");
        when(toolRegistry.description("bash")).thenReturn("bash desc");

        ChatRecordStore store = newStore(toolRegistry);
        ChatDetailResponse detail = store.loadChat(chatId, true);

        List<Map<String, Object>> toolSnapshots = detail.events().stream()
                .filter(event -> "tool.snapshot".equals(event.get("type")))
                .toList();
        List<Map<String, Object>> toolResults = detail.events().stream()
                .filter(event -> "tool.result".equals(event.get("type")))
                .toList();

        assertThat(toolSnapshots).hasSize(1);
        assertThat(toolSnapshots.getFirst())
                .containsEntry("toolName", "bash")
                .containsEntry("toolLabel", "bash label")
                .containsEntry("toolDescription", "bash desc")
                .doesNotContainKey("toolParams");
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.getFirst()).containsEntry("toolId", "tool_visible_1");
        assertThat(detail.events().stream()
                .anyMatch(event -> "_plan_add_tasks_".equals(event.get("toolName")))).isFalse();
    }

    @Test
    void loadChatShouldOmitToolLabelWhenDescriptorHasNoLabel() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174198";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "无标签工具会话", 1707001100000L, 1707001100000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_198", query("run_198", chatId, "执行命令", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_198", "react", 1, null,
                1707001100000L,
                List.of(
                        userMessage("执行命令", 1707001100000L),
                        assistantToolCallMessage("bash", "call_visible_2", "{\"command\":\"pwd\"}", 1707001100001L, "tool_visible_2", null),
                        toolMessage("bash", "call_visible_2", "{\"ok\":true}", 1707001100002L, "tool_visible_2", null)
                )));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.descriptor("bash")).thenReturn(Optional.of(descriptor("bash", null, true)));
        when(toolRegistry.label("bash")).thenReturn(null);
        when(toolRegistry.description("bash")).thenReturn("bash desc");

        ChatRecordStore store = newStore(toolRegistry);
        ChatDetailResponse detail = store.loadChat(chatId, true);

        Map<String, Object> toolSnapshot = detail.events().stream()
                .filter(event -> "tool.snapshot".equals(event.get("type")))
                .findFirst()
                .orElseThrow();

        assertThat(toolSnapshot).containsEntry("toolDescription", "bash desc");
        assertThat(toolSnapshot).doesNotContainKey("toolLabel");
        assertThat(toolSnapshot).doesNotContainKey("toolParams");
    }

    @Test
    void loadChatShouldCollectReferencesFromRunQuery() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174012";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "引用会话", 1707000200000L, 1707000200000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_003", query("run_003", chatId, "你好", List.of(
                Map.of("id", "ref_001", "type", "url", "name", "A", "url", "https://example.com/a"),
                Map.of("id", "ref_001", "type", "url", "name", "B", "url", "https://example.com/b")
        ))));
        writeJsonLine(historyPath, stepLine(chatId, "run_003", "oneshot", 1, null,
                1707000200000L,
                List.of(
                        userMessage("你好", 1707000200000L),
                        assistantContentMessage("你好", 1707000200001L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);

        assertThat(detail.rawMessages()).isNull();
        assertThat(detail.events()).isNotNull();
        assertThat(detail.references()).isNotNull();
        assertThat(detail.references()).hasSize(1);
        assertThat(detail.references().getFirst().id()).isEqualTo("ref_001");
    }

    @Test
    void loadChatShouldIncludeCurrentChatDirectoryAssets() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174013";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "资产会话", 1707000200000L, 1707000200000L);

        Files.createDirectories(chatDir.resolve(chatId));
        Files.write(chatDir.resolve(chatId).resolve("cover.png"), new byte[]{1});

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);

        assertThat(detail.references()).isNotNull();
        assertThat(detail.references()).extracting(reference -> reference.url())
                .contains("/api/resource?file=" + chatId + "%2Fcover.png");
    }

    @Test
    void listChatsShouldUseUpdatedAtAndFallbackToCreatedAtForLegacyRecords() throws Exception {
        String firstChat = "123e4567-e89b-12d3-a456-426614174013";
        String secondChat = "123e4567-e89b-12d3-a456-426614174014";
        ChatRecordStore store = newStore();
        store.ensureChat(firstChat, "demo", "Demo Agent", "legacy");
        store.ensureChat(secondChat, "demo", "Demo Agent", "active");
        store.onRunCompleted(new ChatRecordStore.RunCompletion(firstChat, "run-1", "legacy", "legacy", 1000L));
        store.onRunCompleted(new ChatRecordStore.RunCompletion(secondChat, "run-2", "active", "active", 2000L));
        List<ChatSummaryResponse> chats = store.listChats();

        assertThat(chats).hasSize(2);
        assertThat(chats.getFirst().chatId()).isEqualTo(secondChat);
        assertThat(chats.getFirst().chatName()).isEqualTo("active");
        assertThat(chats.getFirst().agentKey()).isEqualTo("demo");
        assertThat(chats.getFirst().teamId()).isNull();
        assertThat(chats.getFirst().updatedAt()).isGreaterThan(0L);
        assertThat(chats.getFirst().lastRunId()).isEqualTo("run-2");
        assertThat(chats.getFirst().readStatus()).isEqualTo(0);
        assertThat(chats.get(1).chatId()).isEqualTo(firstChat);
        assertThat(chats.get(1).chatName()).isEqualTo("legacy");
        assertThat(chats.get(1).agentKey()).isEqualTo("demo");
        assertThat(chats.get(1).teamId()).isNull();
        assertThat(chats.get(1).updatedAt()).isGreaterThan(0L);
        assertThat(chats.get(1).lastRunId()).isEqualTo("run-1");
    }

    @Test
    void ensureChatShouldRefreshUpdatedAtForSameBinding() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174015";
        ChatRecordStore store = newStore();
        ChatRecordStore.ChatSummary first = store.ensureChat(chatId, "demo", "Demo Agent", "dup-1");
        ChatRecordStore.ChatSummary second = store.ensureChat(chatId, "demo", "Demo Agent", "dup-2");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.agentKey()).isEqualTo("demo");
        assertThat(second.updatedAt()).isGreaterThanOrEqualTo(first.updatedAt());
    }

    @Test
    void ensureChatShouldRejectBindingDrift() {
        String chatId = "123e4567-e89b-12d3-a456-426614174022";
        ChatRecordStore store = newStore();
        store.ensureChat(chatId, "demo", "Demo Agent", "seed");

        assertThatThrownBy(() -> store.ensureChat(chatId, "other", "Other Agent", "drift"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding mismatch");
    }

    @Test
    void ensureChatShouldPersistTeamBindingAndReplayQueryTeamId() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174021";
        String teamId = "a1b2c3d4e5f6";
        ChatRecordStore store = newStore();

        ChatRecordStore.ChatSummary summary = store.ensureChat(chatId, null, null, teamId, "team chat");
        assertThat(summary.teamId()).isEqualTo(teamId);
        assertThat(summary.agentKey()).isNull();

        Path historyPath = tempDir.resolve("chats").resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_team_1", query("run_team_1", chatId, "team hello", List.of(), teamId)));
        writeJsonLine(historyPath, stepLine(chatId, "run_team_1", "oneshot", 1, null,
                1707000700000L,
                List.of(
                        userMessage("team hello", 1707000700000L),
                        assistantContentMessage("team reply", 1707000700001L)
                )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> requestQuery = detail.events().stream()
                .filter(event -> "request.query".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(requestQuery).containsEntry("teamId", teamId);

        List<ChatSummaryResponse> chats = store.listChats();
        assertThat(chats).hasSize(1);
        assertThat(chats.getFirst().teamId()).isEqualTo(teamId);
        assertThat(chats.getFirst().agentKey()).isNull();
    }

    @Test
    void initializeDatabaseShouldPlaceRelativeSqliteFileUnderChatDir() {
        ChatStorageProperties properties = new ChatStorageProperties();
        Path chatDir = tempDir.resolve("chats");
        properties.setDir(chatDir.toString());
        properties.getIndex().setSqliteFile("chats.db");

        ChatRecordStore store = new ChatRecordStore(objectMapper, properties);
        store.initializeDatabase();

        assertThat(chatDir.resolve("chats.db")).exists();
    }

    @Test
    void initializeDatabaseShouldBackupAndRebuildWhenChatsSchemaIsIncompatible() throws Exception {
        Path chatDir = tempDir.resolve("legacy-chats");
        Files.createDirectories(chatDir);
        Path dbPath = chatDir.resolve("chats.db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS CHATS (
                      CHAT_ID_ TEXT PRIMARY KEY,
                      CHAT_NAME_ TEXT NOT NULL,
                      AGENT_KEY_ TEXT NOT NULL,
                      CREATED_AT_ INTEGER NOT NULL,
                      UPDATED_AT_ INTEGER NOT NULL,
                      LAST_RUN_ID_ VARCHAR(12) NOT NULL,
                      LAST_RUN_CONTENT_ TEXT NOT NULL DEFAULT '',
                      READ_STATUS_ INTEGER NOT NULL DEFAULT 1,
                      READ_AT_ INTEGER
                    )
                    """);
        }

        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(chatDir.toString());
        properties.getIndex().setSqliteFile(dbPath.toString());
        ChatRecordStore store = new ChatRecordStore(objectMapper, properties);
        store.initializeDatabase();

        assertThat(dbPath).exists();
        try (java.util.stream.Stream<Path> stream = Files.list(chatDir)) {
            List<Path> backups = stream
                    .filter(path -> path.getFileName().toString().startsWith("chats.db.bak."))
                    .toList();
            assertThat(backups).hasSize(1);
        }
    }

    @Test
    void initializeDatabaseShouldFailWhenAutoRebuildIsDisabled() throws Exception {
        Path chatDir = tempDir.resolve("legacy-chats-disabled");
        Files.createDirectories(chatDir);
        Path dbPath = chatDir.resolve("chats.db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS CHATS (
                      CHAT_ID_ TEXT PRIMARY KEY,
                      CHAT_NAME_ TEXT NOT NULL,
                      AGENT_KEY_ TEXT NOT NULL,
                      CREATED_AT_ INTEGER NOT NULL,
                      UPDATED_AT_ INTEGER NOT NULL,
                      LAST_RUN_ID_ VARCHAR(12) NOT NULL,
                      LAST_RUN_CONTENT_ TEXT NOT NULL DEFAULT '',
                      READ_STATUS_ INTEGER NOT NULL DEFAULT 1,
                      READ_AT_ INTEGER
                    )
                    """);
        }

        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(chatDir.toString());
        properties.getIndex().setSqliteFile(dbPath.toString());
        properties.getIndex().setAutoRebuildOnIncompatibleSchema(false);

        ChatRecordStore store = new ChatRecordStore(objectMapper, properties);
        assertThatThrownBy(store::initializeDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible CHATS schema")
                .hasMessageContaining("Rebuild sqlite chat index");
    }

    @Test
    void markChatReadShouldUpdateReadStatusAndReadAt() {
        String chatId = "123e4567-e89b-12d3-a456-426614174091";
        ChatRecordStore store = newStore();
        store.ensureChat(chatId, "agent-a", "Agent A", "hello a");
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatId, "run-a", "reply a", "hello a", System.currentTimeMillis()));

        List<ChatSummaryResponse> unreadChats = store.listChats();
        assertThat(unreadChats.getFirst().readStatus()).isEqualTo(0);
        assertThat(unreadChats.getFirst().readAt()).isNull();

        ChatRecordStore.MarkChatReadResult readResult = store.markChatRead(chatId);
        assertThat(readResult.chatId()).isEqualTo(chatId);
        assertThat(readResult.readStatus()).isEqualTo(1);
        assertThat(readResult.readAt()).isNotNull();

        List<ChatSummaryResponse> readChats = store.listChats();
        assertThat(readChats.getFirst().readStatus()).isEqualTo(1);
        assertThat(readChats.getFirst().readAt()).isNotNull();
    }

    @Test
    void listChatsShouldSupportIncrementalQueryByLastRunId() {
        String chatA = "123e4567-e89b-12d3-a456-426614174093";
        String chatB = "123e4567-e89b-12d3-a456-426614174094";
        ChatRecordStore store = newStore();
        store.ensureChat(chatA, "agent-a", "Agent A", "hello a");
        store.ensureChat(chatB, "agent-b", "Agent B", "hello b");
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatA, "a1", "reply a", "hello a", System.currentTimeMillis()));
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatB, "a2", "reply b", "hello b", System.currentTimeMillis() + 1));

        List<ChatSummaryResponse> incremental = store.listChats("a1");
        assertThat(incremental).hasSize(1);
        assertThat(incremental.getFirst().chatId()).isEqualTo(chatB);
        assertThat(incremental.getFirst().lastRunId()).isEqualTo("a2");
    }

    @Test
    void listChatsShouldFilterByAgentKeyAndCombineWithIncrementalQuery() {
        String chatA = "123e4567-e89b-12d3-a456-426614174095";
        String chatB = "123e4567-e89b-12d3-a456-426614174096";
        String chatC = "123e4567-e89b-12d3-a456-426614174097";
        ChatRecordStore store = newStore();
        store.ensureChat(chatA, "agent-a", "Agent A", "hello a");
        store.ensureChat(chatB, "agent-b", "Agent B", "hello b");
        store.ensureChat(chatC, "agent-a", "Agent A", "hello c");
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatA, "a1", "reply a", "hello a", System.currentTimeMillis()));
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatB, "a2", "reply b", "hello b", System.currentTimeMillis() + 1));
        store.onRunCompleted(new ChatRecordStore.RunCompletion(chatC, "a3", "reply c", "hello c", System.currentTimeMillis() + 2));

        List<ChatSummaryResponse> filtered = store.listChats(null, "agent-a");
        assertThat(filtered).extracting(ChatSummaryResponse::chatId).containsExactly(chatC, chatA);
        assertThat(filtered).extracting(ChatSummaryResponse::agentKey).containsOnly("agent-a");

        List<ChatSummaryResponse> incremental = store.listChats("a1", "agent-a");
        assertThat(incremental).hasSize(1);
        assertThat(incremental.getFirst().chatId()).isEqualTo(chatC);
        assertThat(incremental.getFirst().lastRunId()).isEqualTo("a3");
        assertThat(incremental.getFirst().agentKey()).isEqualTo("agent-a");
    }

    @Test
    void loadChatShouldEmitChatStartOnlyOnceAcrossMultipleRuns() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174016";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "多轮会话", 1707000300000L, 1707000400000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_004", query("run_004", chatId, "第一轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_004", "oneshot", 1, null,
                1707000300000L,
                List.of(
                        userMessage("第一轮", 1707000300000L),
                        assistantContentMessage("第一轮回答", 1707000300001L)
                )));
        writeJsonLine(historyPath, queryLine(chatId, "run_005", query("run_005", chatId, "第二轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_005", "oneshot", 1, null,
                1707000400000L,
                List.of(
                        userMessage("第二轮", 1707000400000L),
                        assistantContentMessage("第二轮回答", 1707000400001L)
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);

        assertThat(detail.events()).isNotNull();
        assertThat(countType(detail.events(), "chat.start")).isEqualTo(1);
        assertThat(countType(detail.events(), "run.start")).isEqualTo(2);
        assertThat(countType(detail.events(), "run.complete")).isEqualTo(2);
        assertThat(countType(detail.events(), "chat.update")).isEqualTo(0);
    }

    @Test
    void loadChatShouldReplayPlanUpdateWithSeq() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174017";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "计划会话", 1707000500000L, 1707000500000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_006", query("run_006", chatId, "第一轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_006", "plan", 1, null,
                1707000500000L,
                List.of(
                        userMessage("第一轮", 1707000500000L),
                        assistantContentMessage("已创建计划", 1707000500001L)
                ),
                Map.of(
                        "planId", "plan_chat_001",
                        "tasks", List.of(
                                Map.of("taskId", "task0", "description", "收集信息", "status", "init"),
                                Map.of("taskId", "task1", "description", "执行任务", "status", "init")
                        )
                )));

        ChatRecordStore store = newStore();
        ChatDetailResponse detail = store.loadChat(chatId, false);

        Map<String, Object> planUpdate = detail.events().stream()
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
    void appendEventShouldPersistRequestSubmitForHistoryReplay() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174018";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "提交会话", 1707000600000L, 1707000600000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_007", query("run_007", chatId, "第一轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_007", "oneshot", 1, null,
                1707000600000L,
                List.of(
                        userMessage("第一轮", 1707000600000L),
                        assistantContentMessage("等待确认", 1707000600001L)
                )));

        ChatRecordStore store = newStore();
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 99,
                "type", "request.submit",
                "timestamp", 1707000600002L,
                "requestId", "req_submit_001",
                "chatId", chatId,
                "runId", "run_007",
                "toolId", "call_frontend_1",
                "payload", Map.of("confirmed", true)
        )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> requestSubmit = detail.events().stream()
                .filter(event -> "request.submit".equals(event.get("type")))
                .findFirst()
                .orElseThrow();

        assertThat(requestSubmit).containsEntry("requestId", "req_submit_001");
        assertThat(requestSubmit).containsEntry("chatId", chatId);
        assertThat(requestSubmit).containsEntry("runId", "run_007");
        assertThat(requestSubmit).containsEntry("toolId", "call_frontend_1");
        assertThat(requestSubmit).containsKey("seq");
    }

    @Test
    void appendEventShouldPersistArtifactPublishAndExposePublishedAssetAsReference() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174188";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "产出物会话", 1707000600000L, 1707000600000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_009", query("run_009", chatId, "生成报告", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_009", "oneshot", 1, null,
                1707000600000L,
                List.of(
                        userMessage("生成报告", 1707000600000L),
                        assistantContentMessage("报告已生成", 1707000600001L)
                )));

        Path artifactPath = chatDir.resolve(chatId).resolve("artifacts").resolve("run_009").resolve("report.md");
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, "# report\nok\n");

        ChatRecordStore store = newStore();
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 102,
                "type", "artifact.publish",
                "timestamp", 1707000600002L,
                "artifactId", "asset_report_1",
                "chatId", chatId,
                "runId", "run_009",
                "artifact", Map.of(
                        "type", "file",
                        "name", "report.md",
                        "mimeType", "text/markdown",
                        "url", "/api/resource?file=" + chatId + "%2Fartifacts%2Frun_009%2Freport.md"
                )
        )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> artifactPublish = detail.events().stream()
                .filter(event -> "artifact.publish".equals(event.get("type")))
                .findFirst()
                .orElseThrow();

        assertThat(artifactPublish).containsEntry("artifactId", "asset_report_1");
        assertThat(artifactPublish).containsEntry("chatId", chatId);
        assertThat(artifactPublish).containsEntry("runId", "run_009");
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) artifactPublish.get("artifact");
        assertThat(artifact).doesNotContainKeys("id", "meta");
        assertThat(artifactPublish).doesNotContainKey("source");
        assertThat(detail.references()).isNotNull();
        assertThat(detail.references()).extracting(QueryRequest.Reference::name).contains("report.md");
    }

    @Test
    void loadChatShouldReplayArtifactPublishFromStepArtifactsWithoutDuplicatingPersistedEvent() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174189";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "产出物快照会话", 1707000600000L, 1707000600000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_010", query("run_010", chatId, "生成报告", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_010", "execute", 1, "task0",
                1707000600000L,
                List.of(
                        userMessage("生成报告", 1707000600000L),
                        assistantContentMessage("报告已生成", 1707000600001L)
                ),
                null,
                Map.of(
                        "items", List.of(Map.of(
                                "artifactId", "asset_report_2",
                                "type", "file",
                                "name", "report.md",
                                "mimeType", "text/markdown",
                                "url", "/api/resource?file=" + chatId + "%2Fartifacts%2Frun_010%2Freport.md"
                        ))
                )));

        Path artifactPath = chatDir.resolve(chatId).resolve("artifacts").resolve("run_010").resolve("report.md");
        Files.createDirectories(artifactPath.getParent());
        Files.writeString(artifactPath, "# report\nok\n");

        ChatRecordStore store = newStore();
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 102,
                "type", "artifact.publish",
                "timestamp", 1707000600002L,
                "artifactId", "asset_report_2",
                "chatId", chatId,
                "runId", "run_010",
                "artifact", Map.of(
                        "type", "file",
                        "name", "report.md",
                        "mimeType", "text/markdown",
                        "url", "/api/resource?file=" + chatId + "%2Fartifacts%2Frun_010%2Freport.md"
                )
        )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        assertThat(detail.events().stream()
                .filter(event -> "artifact.publish".equals(event.get("type"))))
                .hasSize(1);
    }

    @Test
    void loadChatShouldPreserveLegacyRunCompleteErrorPayloadWithoutConversion() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174111";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "错误会话", 1707000600000L, 1707000600000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_007_error", query("run_007_error", chatId, "第一轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_007_error", "oneshot", 1, null,
                1707000600000L,
                List.of(
                        userMessage("第一轮", 1707000600000L),
                        assistantContentMessage("处理中", 1707000600001L)
                )));

        ChatRecordStore store = newStore();
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 100,
                "type", "run.complete",
                "timestamp", 1707000600002L,
                "runId", "run_007_error",
                "finishReason", "timeout",
                "error", Map.of(
                        "code", "run_timeout",
                        "message", "运行超时，本次执行已结束。已运行 301000ms，超过 runTimeoutMs=300000。",
                        "scope", "run",
                        "category", "timeout",
                        "diagnostics", Map.of(
                                "elapsedMs", 301000L,
                                "timeoutMs", 300000L
                        )
                )
        )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> runComplete = detail.events().stream()
                .filter(event -> "run.complete".equals(event.get("type")))
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(detail.events()).noneMatch(event -> "run.error".equals(event.get("type")));
        assertThat(runComplete).containsEntry("finishReason", "timeout");
        assertThat(runComplete).containsKey("error");
        assertThat(runComplete.get("error")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) runComplete.get("error");
        assertThat(error).containsEntry("code", "run_timeout");
        assertThat(error).containsEntry("scope", "run");
        assertThat(error).containsEntry("category", "timeout");
    }

    @Test
    void appendEventShouldPersistRequestSteerAndRunCancelForHistoryReplay() throws Exception {
        String chatId = "123e4567-e89b-12d3-a456-426614174019";
        Path chatDir = tempDir.resolve("chats");
        writeIndex(chatDir, chatId, "引导会话", 1707000700000L, 1707000700000L);

        Path historyPath = chatDir.resolve(chatId + ".jsonl");
        writeJsonLine(historyPath, queryLine(chatId, "run_008", query("run_008", chatId, "第一轮", List.of())));
        writeJsonLine(historyPath, stepLine(chatId, "run_008", "oneshot", 1, null,
                1707000700000L,
                List.of(
                        userMessage("第一轮", 1707000700000L),
                        assistantContentMessage("处理中", 1707000700001L)
                )));

        ChatRecordStore store = newStore();
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 100,
                "type", "request.steer",
                "timestamp", 1707000700002L,
                "requestId", "req_steer_001",
                "chatId", chatId,
                "runId", "run_008",
                "steerId", "steer_001",
                "message", "继续但更谨慎",
                "role", "user"
        )));
        store.appendEvent(chatId, objectMapper.writeValueAsString(Map.of(
                "seq", 101,
                "type", "run.cancel",
                "timestamp", 1707000700003L,
                "runId", "run_008"
        )));

        ChatDetailResponse detail = store.loadChat(chatId, false);
        Map<String, Object> requestSteer = detail.events().stream()
                .filter(event -> "request.steer".equals(event.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> runCancel = detail.events().stream()
                .filter(event -> "run.cancel".equals(event.get("type")))
                .findFirst()
                .orElseThrow();

        assertThat(requestSteer).containsEntry("requestId", "req_steer_001");
        assertThat(requestSteer).containsEntry("chatId", chatId);
        assertThat(requestSteer).containsEntry("runId", "run_008");
        assertThat(requestSteer).containsEntry("steerId", "steer_001");
        assertThat(requestSteer).containsEntry("message", "继续但更谨慎");
        assertThat(requestSteer).containsEntry("role", "user");
        assertThat(runCancel).containsEntry("runId", "run_008");
        assertThat(detail.events()).extracting(event -> event.get("type"))
                .contains("request.steer", "run.cancel")
                .doesNotContain("run.complete");
    }

    @Test
    void loadChatShouldAcceptNonUuidChatId() {
        ChatRecordStore store = newStore();
        // non-UUID chatId is now accepted; loadChat returns empty for missing chat
        assertThatThrownBy(() -> store.loadChat("not-a-uuid", false))
                .isInstanceOf(ChatNotFoundException.class);
    }

    @Test
    void loadChatShouldThrowNotFoundForMissingChat() {
        ChatRecordStore store = newStore();
        assertThatThrownBy(() -> store.loadChat("123e4567-e89b-12d3-a456-426614174099", false))
                .isInstanceOf(ChatNotFoundException.class);
    }

    @Test
    void ensureChatShouldAllowUnboundPlaceholderAndBindOnFirstMessage() {
        String chatId = "123e4567-e89b-12d3-a456-426614174098";
        ChatRecordStore store = newStore();

        ChatRecordStore.ChatSummary placeholder = store.ensureChat(chatId, null, null, null, null);
        assertThat(placeholder.created()).isTrue();
        assertThat(placeholder.agentKey()).isNull();
        assertThat(placeholder.teamId()).isNull();

        ChatRecordStore.ChatSummary bound = store.ensureChat(chatId, "demo", "Demo Agent", null, "hello");
        assertThat(bound.created()).isFalse();
        assertThat(bound.agentKey()).isEqualTo("demo");
        assertThat(store.findBoundAgentKey(chatId)).contains("demo");
    }

    private ChatRecordStore newStore() {
        return newStore(null);
    }

    private ChatRecordStore newStore(ToolRegistry toolRegistry) {
        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setDir(tempDir.resolve("chats").toString());
        properties.getIndex().setSqliteFile(tempDir.resolve("chats").resolve("chats.db").toString());
        ChatAssetCatalogService chatAssetCatalogService =
                new ChatAssetCatalogService(new ChatDataPathService(properties));
        ChatRecordStore store = new ChatRecordStore(objectMapper, properties, toolRegistry, chatAssetCatalogService);
        store.initializeDatabase();
        return store;
    }

    private ToolDescriptor descriptor(String name, boolean clientVisible) {
        return descriptor(name, name + " label", clientVisible);
    }

    private ToolDescriptor descriptor(String name, String label, boolean clientVisible) {
        return new ToolDescriptor(
                name,
                label,
                name + " desc",
                "",
                Map.of("type", "object"),
                false,
                clientVisible,
                false,
                null,
                "local",
                null,
                null,
                "test://tool"
        );
    }

    private void writeIndex(Path chatDir, String chatId, String chatName, long createdAt, long updatedAt) throws Exception {
        Files.createDirectories(chatDir);
            Path dbPath = chatDir.resolve("chats.db");
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS CHATS (
                      CHAT_ID_ TEXT PRIMARY KEY,
                      CHAT_NAME_ TEXT NOT NULL,
                      AGENT_KEY_ TEXT NOT NULL,
                      TEAM_ID_ TEXT,
                      CREATED_AT_ INTEGER NOT NULL,
                      UPDATED_AT_ INTEGER NOT NULL,
                      LAST_RUN_ID_ VARCHAR(12) NOT NULL,
                      LAST_RUN_CONTENT_ TEXT NOT NULL DEFAULT '',
                      READ_STATUS_ INTEGER NOT NULL DEFAULT 1,
                      READ_AT_ INTEGER
                    )
                    """);
            try (java.sql.PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO CHATS(
                        CHAT_ID_, CHAT_NAME_, AGENT_KEY_, TEAM_ID_,
                        CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                    ) VALUES (?, ?, ?, ?, ?, ?, '', '', 1, ?)
                    ON CONFLICT(CHAT_ID_) DO UPDATE SET
                        CHAT_NAME_ = excluded.CHAT_NAME_,
                        AGENT_KEY_ = excluded.AGENT_KEY_,
                        TEAM_ID_ = excluded.TEAM_ID_,
                        CREATED_AT_ = excluded.CREATED_AT_,
                        UPDATED_AT_ = excluded.UPDATED_AT_
                    """)) {
                upsert.setString(1, chatId);
                upsert.setString(2, chatName);
                upsert.setString(3, "demo");
                upsert.setObject(4, null);
                upsert.setLong(5, createdAt);
                upsert.setLong(6, updatedAt);
                upsert.setLong(7, updatedAt);
                upsert.executeUpdate();
            }
        }
    }

    private void writeJsonLine(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        String line = objectMapper.writeValueAsString(value) + System.lineSeparator();
        Files.writeString(path, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private Map<String, Object> queryLine(String chatId, String runId, Map<String, Object> query) {
        return queryLine(chatId, runId, query, false);
    }

    private Map<String, Object> queryLine(String chatId, String runId, Map<String, Object> query, boolean hidden) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_type", "query");
        line.put("chatId", chatId);
        line.put("runId", runId);
        line.put("updatedAt", System.currentTimeMillis());
        if (hidden) {
            line.put("hidden", true);
        }
        line.put("query", query);
        return line;
    }

    private Map<String, Object> stepLine(
            String chatId,
            String runId,
            String stage,
            int seq,
            String taskId,
            long updatedAt,
            List<Map<String, Object>> messages
    ) {
        return stepLine(chatId, runId, stage, seq, taskId, updatedAt, messages, null, null);
    }

    private Map<String, Object> stepLine(
            String chatId,
            String runId,
            String stage,
            int seq,
            String taskId,
            long updatedAt,
            List<Map<String, Object>> messages,
            Map<String, Object> plan
    ) {
        return stepLine(chatId, runId, stage, seq, taskId, updatedAt, messages, plan, null);
    }

    private Map<String, Object> stepLine(
            String chatId,
            String runId,
            String stage,
            int seq,
            String taskId,
            long updatedAt,
            List<Map<String, Object>> messages,
            Map<String, Object> plan,
            Map<String, Object> artifacts
    ) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_type", "step");
        line.put("chatId", chatId);
        line.put("runId", runId);
        line.put("_stage", stage);
        line.put("_seq", seq);
        if (taskId != null) {
            line.put("taskId", taskId);
        }
        line.put("updatedAt", updatedAt);
        line.put("messages", messages);
        if (plan != null && !plan.isEmpty()) {
            line.put("plan", plan);
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            line.put("artifacts", artifacts);
        }
        return line;
    }

    private Map<String, Object> query(
            String requestId,
            String chatId,
            String message,
            List<Map<String, Object>> references
    ) {
        return query(requestId, chatId, message, references, null);
    }

    private Map<String, Object> query(
            String requestId,
            String chatId,
            String message,
            List<Map<String, Object>> references,
            String teamId
    ) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("requestId", requestId);
        query.put("chatId", chatId);
        query.put("agentKey", "demo");
        if (teamId != null) {
            query.put("teamId", teamId);
        }
        query.put("role", "user");
        query.put("message", message);
        query.put("references", references);
        query.put("params", Map.of("k", "v"));
        query.put("scene", Map.of("url", "https://example.com", "title", "demo"));
        query.put("stream", true);
        return query;
    }

    private Map<String, Object> userMessage(String text, long ts) {
        return Map.of(
                "role", "user",
                "content", List.of(textPart(text)),
                "ts", ts
        );
    }

    private Map<String, Object> assistantReasoningMessage(String text, long ts) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("reasoning_content", List.of(textPart(text)));
        message.put("ts", ts);
        message.put("_reasoningId", "reasoning_short_1");
        message.put("_timing", 12);
        message.put("_usage", usage());
        return message;
    }

    private Map<String, Object> assistantContentMessage(String text, long ts) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", List.of(textPart(text)));
        message.put("ts", ts);
        message.put("_contentId", "content_short_1");
        message.put("_timing", 12);
        message.put("_usage", usage());
        return message;
    }

    private Map<String, Object> assistantToolCallMessage(
            String toolName,
            String toolCallId,
            String arguments,
            long ts,
            String toolId,
            String actionId
    ) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolName);
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", toolCallId);
        call.put("type", "function");
        call.put("function", function);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("tool_calls", List.of(call));
        message.put("ts", ts);
        message.put("_timing", 20);
        message.put("_usage", usage());
        if (toolId != null) {
            message.put("_toolId", toolId);
        }
        if (actionId != null) {
            message.put("_actionId", actionId);
        }
        return message;
    }

    private Map<String, Object> toolMessage(
            String toolName,
            String toolCallId,
            String result,
            long ts,
            String toolId,
            String actionId
    ) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("name", toolName);
        message.put("tool_call_id", toolCallId);
        message.put("content", List.of(textPart(result)));
        message.put("ts", ts);
        message.put("_timing", 8);
        if (toolId != null) {
            message.put("_toolId", toolId);
        }
        if (actionId != null) {
            message.put("_actionId", actionId);
        }
        return message;
    }

    private Map<String, Object> textPart(String text) {
        return Map.of("type", "text", "text", text);
    }

    private Map<String, Object> usage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", null);
        usage.put("output_tokens", null);
        usage.put("total_tokens", null);
        return usage;
    }

    private long countType(List<Map<String, Object>> events, String type) {
        return events.stream().filter(event -> type.equals(event.get("type"))).count();
    }
}
