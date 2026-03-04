package com.linlay.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话记录读取与快照回放服务。
 * <p>
 * 负责维护聊天索引（会话列表元数据）、读取运行历史文件、并将持久化消息转换为
 * 前端可消费的事件快照与原始消息列表。该类不参与实时 SSE 发送，仅处理存储侧视图重建。
 */
@Service
public class ChatRecordStore {

    private static final Logger log = LoggerFactory.getLogger(ChatRecordStore.class);
    private static final String TABLE_CHATS = "CHATS";
    private static final List<ColumnSchema> CHATS_SCHEMA = List.of(
            new ColumnSchema("CHAT_ID_", "TEXT", 0, null, 1),
            new ColumnSchema("CHAT_NAME_", "TEXT", 1, null, 0),
            new ColumnSchema("AGENT_KEY_", "TEXT", 1, null, 0),
            new ColumnSchema("TEAM_ID_", "TEXT", 0, null, 0),
            new ColumnSchema("CREATED_AT_", "INTEGER", 1, null, 0),
            new ColumnSchema("UPDATED_AT_", "INTEGER", 1, null, 0),
            new ColumnSchema("LAST_RUN_ID_", "VARCHAR(12)", 1, null, 0),
            new ColumnSchema("LAST_RUN_CONTENT_", "TEXT", 1, "''", 0),
            new ColumnSchema("READ_STATUS_", "INTEGER", 1, "1", 0),
            new ColumnSchema("READ_AT_", "INTEGER", 0, null, 0)
    );

    private static final String CREATE_CHATS_SQL = """
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
            """;
    private static final String CREATE_CHATS_LAST_RUN_ID_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS IDX_CHATS_LAST_RUN_ID_
              ON CHATS(LAST_RUN_ID_)
            """;

    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryProperties properties;
    private final Object lock = new Object();

    public ChatRecordStore(ObjectMapper objectMapper, ChatWindowMemoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void initializeDatabase() {
        synchronized (lock) {
            Path dbPath = resolveSqlitePath();
            Path parent = dbPath.getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot create sqlite directory for " + dbPath, ex);
            }

            try {
                initializeOrValidateSchema();
            } catch (IncompatibleChatsSchemaException ex) {
                if (!isAutoRebuildOnIncompatibleSchema()) {
                    throw ex;
                }
                rebuildIncompatibleSchema(dbPath, ex);
            }
        }
    }

    private void initializeOrValidateSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_CHATS_SQL);
            statement.execute(CREATE_CHATS_LAST_RUN_ID_INDEX_SQL);
            validateChatsSchema(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot initialize sqlite chat index", ex);
        }
    }

    private void rebuildIncompatibleSchema(Path dbPath, IncompatibleChatsSchemaException cause) {
        Path backupPath = backupIncompatibleDb(dbPath);
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS CHATS");
            statement.execute(CREATE_CHATS_SQL);
            statement.execute(CREATE_CHATS_LAST_RUN_ID_INDEX_SQL);
            validateChatsSchema(connection);
            log.warn(
                    "Detected incompatible CHATS schema ({}), rebuilt sqlite chat index. dbPath={}, backupPath={}",
                    cause.getMessage(),
                    dbPath,
                    backupPath
            );
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot rebuild sqlite chat index at " + dbPath, ex);
        }
    }

    private Path backupIncompatibleDb(Path dbPath) {
        Path backupPath = resolveBackupPath(dbPath);
        try {
            Files.copy(dbPath, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
            return backupPath;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Cannot backup incompatible sqlite chat index from " + dbPath + " to " + backupPath,
                    ex
            );
        }
    }

    private Path resolveBackupPath(Path dbPath) {
        String baseName = dbPath.getFileName().toString() + ".bak." + System.currentTimeMillis();
        Path parent = dbPath.getParent();
        Path candidate = parent == null ? Path.of(baseName) : parent.resolve(baseName);
        int suffix = 1;
        while (Files.exists(candidate)) {
            Path next = parent == null
                    ? Path.of(baseName + "." + suffix)
                    : parent.resolve(baseName + "." + suffix);
            candidate = next;
            suffix++;
        }
        return candidate;
    }

    private boolean isAutoRebuildOnIncompatibleSchema() {
        ChatWindowMemoryProperties.IndexProperties index = properties.getIndex();
        return index == null || index.isAutoRebuildOnIncompatibleSchema();
    }

    public ChatSummary ensureChat(String chatId, String firstAgentKey, String firstAgentName, String firstMessage) {
        return ensureChat(chatId, firstAgentKey, firstAgentName, null, firstMessage);
    }

    public ChatSummary ensureChat(String chatId, String firstAgentKey, String firstAgentName, String firstTeamId, String firstMessage) {
        requireValidChatId(chatId);
        String normalizedAgentKey = nullable(firstAgentKey);
        String normalizedTeamId = nullable(firstTeamId);
        validateChatBinding(normalizedAgentKey, normalizedTeamId);
        String normalizedChatName = deriveChatName(firstMessage);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                ChatIndexRecord existing = findChatRecordById(connection, chatId);
                boolean created = existing == null;
                ChatIndexRecord record = created ? new ChatIndexRecord() : existing;
                if (created) {
                    record.chatId = chatId;
                    record.chatName = normalizedChatName;
                    record.agentKey = normalizedAgentKey;
                    record.teamId = normalizedTeamId;
                    record.createdAt = now;
                    record.updatedAt = now;
                    record.lastRunContent = StringUtils.hasText(firstMessage) ? firstMessage.trim() : "";
                    record.lastRunId = "";
                    record.readStatus = 1;
                    record.readAt = now;
                } else {
                    validateBindingDrift(record, normalizedAgentKey, normalizedTeamId);
                    if (!StringUtils.hasText(record.chatName)) {
                        record.chatName = normalizedChatName;
                    }
                    if (!StringUtils.hasText(record.agentKey) && !StringUtils.hasText(record.teamId)) {
                        record.agentKey = normalizedAgentKey;
                        record.teamId = normalizedTeamId;
                    } else if (StringUtils.hasText(record.agentKey)) {
                        record.teamId = null;
                    } else if (StringUtils.hasText(record.teamId)) {
                        record.agentKey = null;
                    }
                    if (record.createdAt <= 0) {
                        record.createdAt = now;
                    }
                    record.updatedAt = now;
                    if (!StringUtils.hasText(record.lastRunId)) {
                        record.lastRunId = "";
                    }
                    if (!StringUtils.hasText(record.lastRunContent) && StringUtils.hasText(firstMessage)) {
                        record.lastRunContent = firstMessage.trim();
                    }
                    if (record.readStatus != 0 && record.readStatus != 1) {
                        record.readStatus = 1;
                    }
                }
                upsertChatIndex(connection, record);
                connection.commit();
                return toChatSummary(record, created);
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot upsert chat index for chatId=" + chatId, ex);
            }
        }
    }

    public void appendEvent(String chatId, String eventData) {
        if (!isValidChatId(chatId) || !StringUtils.hasText(eventData)) {
            return;
        }
        JsonNode node = parseLine(eventData);
        if (node == null || !node.isObject()) {
            return;
        }

        String type = textValue(node.get("type"));
        if (!isPersistedEventType(type)) {
            return;
        }
        String runId = textValue(node.get("runId"));
        if (!StringUtils.hasText(runId)) {
            return;
        }

        long timestamp = node.path("timestamp").asLong(System.currentTimeMillis());
        Map<String, Object> event = objectMapper.convertValue(
                node,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
        );

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_type", "event");
        line.put("chatId", chatId);
        line.put("runId", runId);
        line.put("updatedAt", timestamp > 0 ? timestamp : System.currentTimeMillis());
        line.put("event", event);

        synchronized (lock) {
            appendJsonLine(resolveHistoryPath(chatId), line);
        }
    }

    public Optional<String> findBoundAgentKey(String chatId) {
        if (!isValidChatId(chatId)) {
            return Optional.empty();
        }
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                ChatIndexRecord record = findChatRecordById(connection, chatId);
                if (record == null || StringUtils.hasText(record.teamId) || !StringUtils.hasText(record.agentKey)) {
                    return Optional.empty();
                }
                return Optional.of(record.agentKey);
            } catch (SQLException ex) {
                log.warn("Cannot query bound agent for chatId={}", chatId, ex);
                return Optional.empty();
            }
        }
    }

    public Optional<String> findBoundTeamId(String chatId) {
        if (!isValidChatId(chatId)) {
            return Optional.empty();
        }
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                ChatIndexRecord record = findChatRecordById(connection, chatId);
                if (record == null || StringUtils.hasText(record.agentKey) || !StringUtils.hasText(record.teamId)) {
                    return Optional.empty();
                }
                return Optional.of(record.teamId);
            } catch (SQLException ex) {
                log.warn("Cannot query bound team for chatId={}", chatId, ex);
                return Optional.empty();
            }
        }
    }

    public void onRunCompleted(RunCompletion completion) {
        if (completion == null || !isValidChatId(completion.chatId()) || !StringUtils.hasText(completion.runId())) {
            return;
        }
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                ChatIndexRecord record = findChatRecordById(connection, completion.chatId());
                if (record == null) {
                    connection.rollback();
                    return;
                }

                long eventAt = completion.completedAt() > 0 ? completion.completedAt() : System.currentTimeMillis();
                String assistantContent = nullable(completion.assistantContent());
                String fallbackUserMessage = nullable(completion.fallbackUserMessage());
                String mergedContent = assistantContent != null
                        ? assistantContent
                        : (fallbackUserMessage != null ? fallbackUserMessage : nullable(record.lastRunContent));
                if (mergedContent == null) {
                    mergedContent = "";
                }

                record.lastRunId = completion.runId().trim();
                record.updatedAt = eventAt;
                record.lastRunContent = mergedContent;
                record.readStatus = 0;
                record.readAt = null;
                upsertChatIndex(connection, record);
                connection.commit();
            } catch (Exception ex) {
                log.warn("Cannot update run completion index chatId={}, runId={}", completion.chatId(), completion.runId(), ex);
            }
        }
    }

    public List<ChatSummaryResponse> listChats() {
        return listChats(null);
    }

    public List<ChatSummaryResponse> listChats(String lastRunId) {
        boolean incremental = StringUtils.hasText(lastRunId);
        String sql = incremental
                ? """
                SELECT CHAT_ID_, CHAT_NAME_, AGENT_KEY_, TEAM_ID_,
                       CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                FROM CHATS
                WHERE LAST_RUN_ID_ > ?
                ORDER BY LAST_RUN_ID_ ASC
                """
                : """
                SELECT CHAT_ID_, CHAT_NAME_, AGENT_KEY_, TEAM_ID_,
                       CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                FROM CHATS
                ORDER BY LAST_RUN_ID_ DESC, UPDATED_AT_ DESC
                """;
        synchronized (lock) {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                if (incremental) {
                    statement.setString(1, lastRunId.trim());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<ChatSummaryResponse> responses = new ArrayList<>();
                    while (resultSet.next()) {
                        ChatIndexRecord record = mapChatIndexRecord(resultSet);
                        ChatSummary summary = toChatSummary(record);
                        responses.add(new ChatSummaryResponse(
                                summary.chatId(),
                                summary.chatName(),
                                summary.agentKey(),
                                summary.teamId(),
                                summary.createdAt(),
                                summary.updatedAt(),
                                summary.lastRunId(),
                                summary.lastRunContent(),
                                summary.readStatus(),
                                summary.readAt()
                        ));
                    }
                    return List.copyOf(responses);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot list chats from sqlite", ex);
            }
        }
    }

    public MarkChatReadResult markChatRead(String chatId) {
        requireValidChatId(chatId);
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                ChatIndexRecord record = findChatRecordById(connection, chatId);
                if (record == null) {
                    connection.rollback();
                    throw new ChatNotFoundException(chatId);
                }
                long readAt = System.currentTimeMillis();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE CHATS
                        SET READ_STATUS_ = 1, READ_AT_ = ?
                        WHERE CHAT_ID_ = ?
                        """)) {
                    statement.setLong(1, readAt);
                    statement.setString(2, chatId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new MarkChatReadResult(chatId, 1, readAt);
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot mark chat as read for " + chatId, ex);
            }
        }
    }

    public ChatDetailResponse loadChat(String chatId, boolean includeRawMessages) {
        requireValidChatId(chatId);
        Path historyPath = resolveHistoryPath(chatId);
        synchronized (lock) {
            ChatIndexRecord indexRecord;
            try (Connection connection = openConnection()) {
                indexRecord = findChatRecordById(connection, chatId);
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot query chat index for chatId=" + chatId, ex);
            }

            if (indexRecord == null && !Files.exists(historyPath)) {
                throw new ChatNotFoundException(chatId);
            }

            ChatSummary summary = Optional.ofNullable(indexRecord)
                    .map(this::toChatSummary)
                    .orElseGet(() -> {
                        long createdAt = resolveCreatedAt(historyPath);
                        return new ChatSummary(chatId, chatId, null, null, createdAt, createdAt, "", "", 1, createdAt, false);
                    });

            ParsedChatContent content = readChatContent(
                    historyPath,
                    summary.chatId,
                    summary.chatName
            );

            List<Map<String, Object>> events = List.copyOf(content.events);
            List<Map<String, Object>> rawMessages = includeRawMessages
                    ? List.copyOf(content.rawMessages)
                    : null;
            List<QueryRequest.Reference> references = content.references.isEmpty()
                    ? null
                    : List.copyOf(content.references.values());

            return new ChatDetailResponse(
                    summary.chatId,
                    summary.chatName,
                    null,
                    rawMessages,
                    events,
                    references
            );
        }
    }

    private ParsedChatContent readChatContent(
            Path historyPath,
            String chatId,
            String chatName
    ) {
        ParsedChatContent content = new ParsedChatContent();
        readHistoryLines(historyPath, content);

        content.runs.sort(
                Comparator.comparingLong(this::sortByUpdatedAt)
                        .thenComparingInt(RunSnapshot::lineIndex)
        );

        for (RunSnapshot run : content.runs) {
            for (ChatWindowMemoryStore.StoredMessage message : run.messages) {
                Map<String, Object> raw = toRawMessageMap(run.runId, message);
                if (!raw.isEmpty()) {
                    content.rawMessages.add(raw);
                }
            }
        }
        content.events.addAll(buildSnapshotEvents(chatId, chatName, content.runs));
        return content;
    }

    private void readHistoryLines(Path historyPath, ParsedChatContent content) {
        if (!Files.exists(historyPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(historyPath, resolveCharset());
            // Intermediate structures to group by runId
            LinkedHashMap<String, Map<String, Object>> queryByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, List<StepEntry>> stepsByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, List<PersistedEvent>> eventsByRunId = new LinkedHashMap<>();
            int lineIndex = 0;

            for (String line : lines) {
                if (!StringUtils.hasText(line)) {
                    lineIndex++;
                    continue;
                }

                JsonNode node = parseLine(line);
                if (node == null || !node.isObject()) {
                    lineIndex++;
                    continue;
                }

                String type = node.path("_type").asText("");
                String runId = node.path("runId").asText(null);
                if (!StringUtils.hasText(runId)) {
                    lineIndex++;
                    continue;
                }

                if ("query".equals(type)) {
                    Map<String, Object> query = new LinkedHashMap<>();
                    if (node.has("query") && node.get("query").isObject()) {
                        query = objectMapper.convertValue(
                                node.get("query"),
                                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
                        );
                    }
                    queryByRunId.put(runId, query);
                    collectReferencesFromQuery(query, content);
                    // Ensure run order
                    stepsByRunId.computeIfAbsent(runId, k -> new ArrayList<>());
                    eventsByRunId.computeIfAbsent(runId, k -> new ArrayList<>());
                } else if ("step".equals(type)) {
                    long updatedAt = node.path("updatedAt").asLong(0);
                    String stage = node.path("_stage").asText(null);
                    int seq = node.path("_seq").asInt(0);
                    String taskId = node.has("taskId") && !node.get("taskId").isNull()
                            ? node.path("taskId").asText(null)
                            : null;

                    ChatWindowMemoryStore.SystemSnapshot system = null;
                    if (node.has("system") && !node.get("system").isNull()) {
                        system = objectMapper.treeToValue(node.get("system"), ChatWindowMemoryStore.SystemSnapshot.class);
                    }

                    ChatWindowMemoryStore.PlanSnapshot plan = null;
                    JsonNode planNode = node.has("plan") && !node.get("plan").isNull()
                            ? node.get("plan")
                            : (node.has("planSnapshot") && !node.get("planSnapshot").isNull() ? node.get("planSnapshot") : null);
                    if (planNode != null) {
                        plan = objectMapper.treeToValue(planNode, ChatWindowMemoryStore.PlanSnapshot.class);
                    }

                    List<ChatWindowMemoryStore.StoredMessage> messages = new ArrayList<>();
                    if (node.has("messages") && node.get("messages").isArray()) {
                        for (JsonNode msgNode : node.get("messages")) {
                            ChatWindowMemoryStore.StoredMessage msg = objectMapper.treeToValue(msgNode, ChatWindowMemoryStore.StoredMessage.class);
                            if (msg != null) {
                                messages.add(msg);
                            }
                        }
                    }

                    stepsByRunId.computeIfAbsent(runId, k -> new ArrayList<>())
                            .add(new StepEntry(stage, seq, taskId, updatedAt, system, plan, messages, lineIndex));
                    eventsByRunId.computeIfAbsent(runId, k -> new ArrayList<>());
                } else if ("event".equals(type)) {
                    JsonNode eventNode = node.has("event") && node.get("event").isObject()
                            ? node.get("event")
                            : node;
                    String eventType = textValue(eventNode.get("type"));
                    if (!isPersistedEventType(eventType)) {
                        lineIndex++;
                        continue;
                    }
                    long eventTs = eventNode.path("timestamp").asLong(node.path("updatedAt").asLong(0));
                    Map<String, Object> eventPayload = objectMapper.convertValue(
                            eventNode,
                            objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
                    );
                    eventPayload.remove("seq");
                    eventPayload.remove("type");
                    eventPayload.remove("timestamp");
                    if (!eventPayload.containsKey("chatId")) {
                        String eventChatId = textValue(node.get("chatId"));
                        if (StringUtils.hasText(eventChatId)) {
                            eventPayload.put("chatId", eventChatId);
                        }
                    }
                    stepsByRunId.computeIfAbsent(runId, k -> new ArrayList<>());
                    eventsByRunId.computeIfAbsent(runId, k -> new ArrayList<>())
                            .add(new PersistedEvent(eventType, eventTs, eventPayload, lineIndex));
                }
                lineIndex++;
            }

            // Build RunSnapshots from grouped data
            int runIndex = 0;
            for (Map.Entry<String, List<StepEntry>> entry : stepsByRunId.entrySet()) {
                String runId = entry.getKey();
                List<StepEntry> steps = entry.getValue();
                List<PersistedEvent> persistedEvents = eventsByRunId.getOrDefault(runId, List.of());
                if (steps.isEmpty() && persistedEvents.isEmpty()) {
                    runIndex++;
                    continue;
                }

                if (!steps.isEmpty()) {
                    steps.sort(Comparator.comparingInt(s -> s.seq));
                }

                Map<String, Object> query = queryByRunId.getOrDefault(runId, Map.of());
                long updatedAt = Math.max(
                        steps.stream().mapToLong(s -> s.updatedAt).max().orElse(0),
                        persistedEvents.stream().mapToLong(PersistedEvent::timestamp).max().orElse(0)
                );

                // Flatten all step messages
                List<ChatWindowMemoryStore.StoredMessage> allMessages = new ArrayList<>();
                ChatWindowMemoryStore.SystemSnapshot firstSystem = null;
                ChatWindowMemoryStore.PlanSnapshot latestPlan = null;
                for (StepEntry step : steps) {
                    if (firstSystem == null && step.system != null) {
                        firstSystem = step.system;
                    }
                    if (step.plan != null) {
                        latestPlan = step.plan;
                    }
                    allMessages.addAll(step.messages);
                }

                int firstLineIndex = !steps.isEmpty()
                        ? steps.getFirst().lineIndex
                        : persistedEvents.stream().mapToInt(PersistedEvent::lineIndex).min().orElse(lineIndex);
                content.runs.add(new RunSnapshot(
                        runId,
                        updatedAt,
                        query,
                        firstSystem,
                        latestPlan,
                        List.copyOf(allMessages),
                        List.copyOf(persistedEvents),
                        firstLineIndex
                ));
                runIndex++;
            }
        } catch (Exception ex) {
            log.warn("Cannot read chat history file={}, fallback to empty", historyPath, ex);
        }
    }

    private void collectReferencesFromQuery(Map<String, Object> query, ParsedChatContent content) {
        if (query == null || query.isEmpty()) {
            return;
        }
        Object referencesObject = query.get("references");
        if (!(referencesObject instanceof List<?> referencesList)) {
            return;
        }

        for (Object item : referencesList) {
            if (item == null) {
                continue;
            }
            try {
                QueryRequest.Reference reference = objectMapper.convertValue(item, QueryRequest.Reference.class);
                if (reference == null || !StringUtils.hasText(reference.id())) {
                    continue;
                }
                content.references.putIfAbsent(reference.id().trim(), reference);
            } catch (Exception ignored) {
                // ignore invalid reference item
            }
        }
    }

    private List<Map<String, Object>> buildSnapshotEvents(
            String chatId,
            String chatName,
            List<RunSnapshot> runs
    ) {
        if (runs.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> events = new ArrayList<>();
        long seq = 1L;
        boolean emittedChatStart = false;

        for (RunSnapshot run : runs) {
            long runStartTs = resolveRunStartTimestamp(run);
            long runEndTs = resolveRunEndTimestamp(run, runStartTs);
            long timestampCursor = runStartTs;
            int reasoningIndex = 0;
            int contentIndex = 0;
            int toolIndex = 0;
            int actionIndex = 0;
            Map<String, IdBinding> bindingByCallId = new LinkedHashMap<>();
            List<PersistedEvent> persistedEvents = run.persistedEvents.stream()
                    .sorted(Comparator.comparingLong(PersistedEvent::timestamp)
                            .thenComparingInt(PersistedEvent::lineIndex))
                    .toList();
            int persistedIndex = 0;

            Map<String, Object> requestQueryPayload = buildRequestQueryPayload(chatId, run);
            events.add(event("request.query", timestampCursor, seq++, requestQueryPayload));

            if (!emittedChatStart) {
                timestampCursor = normalizeEventTimestamp(timestampCursor + 1, timestampCursor);
                Map<String, Object> chatStartPayload = new LinkedHashMap<>();
                chatStartPayload.put("chatId", chatId);
                if (StringUtils.hasText(chatName)) {
                    chatStartPayload.put("chatName", chatName);
                }
                events.add(event("chat.start", timestampCursor, seq++, chatStartPayload));
                emittedChatStart = true;
            }

            timestampCursor = normalizeEventTimestamp(timestampCursor + 1, timestampCursor);
            Map<String, Object> runStartPayload = new LinkedHashMap<>();
            runStartPayload.put("runId", run.runId);
            runStartPayload.put("chatId", chatId);
            events.add(event("run.start", timestampCursor, seq++, runStartPayload));

            Map<String, Object> planUpdate = planUpdateEvent(run.plan, chatId, timestampCursor);
            if (!planUpdate.isEmpty()) {
                timestampCursor = normalizeEventTimestamp(((Number) planUpdate.get("timestamp")).longValue(), timestampCursor);
                events.add(planUpdate);
            }

            while (persistedIndex < persistedEvents.size()
                    && persistedEvents.get(persistedIndex).timestamp() <= timestampCursor) {
                PersistedEvent persisted = persistedEvents.get(persistedIndex++);
                long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                timestampCursor = persistedTs;
            }

            for (ChatWindowMemoryStore.StoredMessage message : run.messages) {
                if (message == null || !StringUtils.hasText(message.role)) {
                    continue;
                }
                long messageTs = normalizeEventTimestamp(resolveMessageTimestamp(message, timestampCursor), timestampCursor);
                while (persistedIndex < persistedEvents.size()
                        && persistedEvents.get(persistedIndex).timestamp() <= messageTs) {
                    PersistedEvent persisted = persistedEvents.get(persistedIndex++);
                    long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                    events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                    timestampCursor = persistedTs;
                }
                String role = message.role.trim().toLowerCase();

                if ("assistant".equals(role)) {
                    if (message.reasoningContent != null && !message.reasoningContent.isEmpty()) {
                        String text = textFromContent(message.reasoningContent);
                        if (StringUtils.hasText(text)) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("reasoningId", StringUtils.hasText(message.reasoningId)
                                    ? message.reasoningId
                                    : run.runId + "_r_" + reasoningIndex++);
                            payload.put("text", text);
                            events.add(event("reasoning.snapshot", messageTs, seq++, payload));
                            timestampCursor = messageTs;
                        }
                    }
                    if (message.content != null && !message.content.isEmpty()) {
                        String text = textFromContent(message.content);
                        if (StringUtils.hasText(text)) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("contentId", StringUtils.hasText(message.contentId)
                                    ? message.contentId
                                    : run.runId + "_c_" + contentIndex++);
                            payload.put("text", text);
                            events.add(event("content.snapshot", messageTs, seq++, payload));
                            timestampCursor = messageTs;
                        }
                    }
                    if (message.toolCalls != null && !message.toolCalls.isEmpty()) {
                        for (ChatWindowMemoryStore.StoredToolCall toolCall : message.toolCalls) {
                            if (toolCall == null || toolCall.function == null || !StringUtils.hasText(toolCall.function.name)) {
                                continue;
                            }
                            IdBinding binding = resolveBindingForAssistantToolCall(run.runId, message, toolCall, toolIndex, actionIndex);
                            if (binding.action) {
                                actionIndex++;
                            } else {
                                toolIndex++;
                            }
                            if (StringUtils.hasText(toolCall.id)) {
                                bindingByCallId.put(toolCall.id.trim(), binding);
                            }

                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put(binding.action ? "actionId" : "toolId", binding.id);
                            payload.put(binding.action ? "actionName" : "toolName", toolCall.function.name);
                            payload.put("arguments", toolCall.function.arguments);

                            if (!binding.action) {
                                payload.put("toolType", StringUtils.hasText(toolCall.type) ? toolCall.type : "function");
                                payload.put("toolApi", null);
                                payload.put("toolParams", toToolParams(toolCall.function.arguments));
                                payload.put("description", null);
                            } else {
                                payload.put("description", null);
                            }

                            timestampCursor = normalizeEventTimestamp(messageTs, timestampCursor);
                            events.add(event(binding.action ? "action.snapshot" : "tool.snapshot", timestampCursor, seq++, payload));
                            messageTs = timestampCursor + 1;
                        }
                    }
                    continue;
                }

                if (!"tool".equals(role)) {
                    continue;
                }

                String result = textFromContent(message.content);
                if (!StringUtils.hasText(result)) {
                    result = "";
                }

                IdBinding binding = resolveBindingForToolResult(run.runId, message, bindingByCallId, toolIndex, actionIndex);
                if (binding == null) {
                    continue;
                }
                if (binding.action) {
                    actionIndex++;
                } else {
                    toolIndex++;
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put(binding.action ? "actionId" : "toolId", binding.id);
                payload.put("result", result);
                timestampCursor = normalizeEventTimestamp(messageTs, timestampCursor);
                events.add(event(binding.action ? "action.result" : "tool.result", timestampCursor, seq++, payload));
            }

            while (persistedIndex < persistedEvents.size()) {
                PersistedEvent persisted = persistedEvents.get(persistedIndex++);
                long persistedTs = normalizeEventTimestamp(persisted.timestamp(), timestampCursor);
                events.add(event(persisted.type(), persistedTs, seq++, persisted.payload()));
                timestampCursor = persistedTs;
            }

            timestampCursor = normalizeEventTimestamp(runEndTs + 1, timestampCursor);
            Map<String, Object> runCompletePayload = new LinkedHashMap<>();
            runCompletePayload.put("runId", run.runId);
            runCompletePayload.put("finishReason", "end_turn");
            events.add(event("run.complete", timestampCursor, seq++, runCompletePayload));
        }

        return List.copyOf(events);
    }

    private Map<String, Object> buildRequestQueryPayload(String chatId, RunSnapshot run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> query = run.query == null ? Map.of() : run.query;

        Object requestId = query.get("requestId");
        payload.put("requestId", textOrFallback(requestId, run.runId));
        payload.put("chatId", textOrFallback(query.get("chatId"), chatId));
        payload.put("role", textOrFallback(query.get("role"), "user"));
        payload.put("message", textOrFallback(query.get("message"), firstUserText(run.messages)));
        putIfNonNull(payload, "agentKey", query.get("agentKey"));
        putIfNonNull(payload, "teamId", query.get("teamId"));
        putIfNonNull(payload, "references", query.get("references"));
        putIfNonNull(payload, "params", query.get("params"));
        putIfNonNull(payload, "scene", query.get("scene"));
        putIfNonNull(payload, "stream", query.get("stream"));
        return payload;
    }

    private IdBinding resolveBindingForAssistantToolCall(
            String runId,
            ChatWindowMemoryStore.StoredMessage message,
            ChatWindowMemoryStore.StoredToolCall toolCall,
            int toolIndex,
            int actionIndex
    ) {
        // First check outer-level message fields (V3.1)
        if (StringUtils.hasText(message.actionId)) {
            return new IdBinding(message.actionId.trim(), true);
        }
        if (StringUtils.hasText(message.toolId)) {
            return new IdBinding(message.toolId.trim(), false);
        }
        // Fallback to inner toolCall fields (V3 compat)
        boolean actionByType = StringUtils.hasText(toolCall.type)
                && "action".equalsIgnoreCase(toolCall.type.trim());
        if (StringUtils.hasText(toolCall.actionId)) {
            return new IdBinding(toolCall.actionId.trim(), true);
        }
        if (StringUtils.hasText(toolCall.toolId)) {
            return new IdBinding(toolCall.toolId.trim(), false);
        }
        if (actionByType && StringUtils.hasText(toolCall.id)) {
            return new IdBinding(toolCall.id.trim(), true);
        }
        if (StringUtils.hasText(toolCall.id)) {
            return new IdBinding(toolCall.id.trim(), false);
        }
        if (actionByType) {
            return new IdBinding(runId + "_action_" + actionIndex, true);
        }
        return new IdBinding(runId + "_tool_" + toolIndex + "_action_" + actionIndex, false);
    }

    private IdBinding resolveBindingForToolResult(
            String runId,
            ChatWindowMemoryStore.StoredMessage message,
            Map<String, IdBinding> bindingByCallId,
            int toolIndex,
            int actionIndex
    ) {
        if (StringUtils.hasText(message.actionId)) {
            return new IdBinding(message.actionId.trim(), true);
        }
        if (StringUtils.hasText(message.toolId)) {
            return new IdBinding(message.toolId.trim(), false);
        }
        if (StringUtils.hasText(message.toolCallId)) {
            IdBinding binding = bindingByCallId.get(message.toolCallId.trim());
            if (binding != null) {
                return binding;
            }
            return new IdBinding(message.toolCallId.trim(), false);
        }
        if (!StringUtils.hasText(message.name)) {
            return null;
        }
        return new IdBinding(runId + "_tool_result_" + toolIndex + "_action_" + actionIndex, false);
    }

    private Object toToolParams(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            return objectMapper.convertValue(parsed, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstUserText(List<ChatWindowMemoryStore.StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (ChatWindowMemoryStore.StoredMessage message : messages) {
            if (message == null || !"user".equalsIgnoreCase(message.role)) {
                continue;
            }
            String text = textFromContent(message.content);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String textFromContent(List<ChatWindowMemoryStore.ContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatWindowMemoryStore.ContentPart contentPart : contentParts) {
            if (contentPart == null || !StringUtils.hasText(contentPart.text)) {
                continue;
            }
            text.append(contentPart.text);
        }
        return text.toString();
    }

    private long resolveRunStartTimestamp(RunSnapshot run) {
        long earliest = Long.MAX_VALUE;
        for (ChatWindowMemoryStore.StoredMessage message : run.messages) {
            if (message != null && message.ts != null && message.ts > 0 && message.ts < earliest) {
                earliest = message.ts;
            }
        }
        if (earliest != Long.MAX_VALUE) {
            return earliest;
        }
        if (run.updatedAt > 0) {
            return run.updatedAt;
        }
        return System.currentTimeMillis();
    }

    private long resolveRunEndTimestamp(RunSnapshot run, long fallbackStart) {
        long latest = Long.MIN_VALUE;
        for (ChatWindowMemoryStore.StoredMessage message : run.messages) {
            if (message != null && message.ts != null && message.ts > 0 && message.ts > latest) {
                latest = message.ts;
            }
        }
        if (latest != Long.MIN_VALUE) {
            return latest;
        }
        if (run.updatedAt > 0) {
            return run.updatedAt;
        }
        return fallbackStart;
    }

    private long resolveMessageTimestamp(ChatWindowMemoryStore.StoredMessage message, long fallback) {
        if (message != null && message.ts != null && message.ts > 0) {
            return message.ts;
        }
        return fallback;
    }

    private long normalizeEventTimestamp(long candidate, long previous) {
        if (candidate <= 0) {
            return previous + 1;
        }
        return Math.max(candidate, previous + 1);
    }

    private long sortByUpdatedAt(RunSnapshot run) {
        if (run == null || run.updatedAt <= 0) {
            return Long.MAX_VALUE;
        }
        return run.updatedAt;
    }

    private Map<String, Object> event(String type, long timestamp, long seq, Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("type", type);
        data.put("timestamp", timestamp);
        if (payload != null && !payload.isEmpty()) {
            data.putAll(payload);
        }
        return data;
    }

    private Map<String, Object> planUpdateEvent(
            ChatWindowMemoryStore.PlanSnapshot planSnapshot,
            String chatId,
            long previousTimestamp
    ) {
        if (planSnapshot == null
                || !StringUtils.hasText(planSnapshot.planId)
                || planSnapshot.tasks == null
                || planSnapshot.tasks.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        for (ChatWindowMemoryStore.PlanTaskSnapshot task : planSnapshot.tasks) {
            if (task == null || !StringUtils.hasText(task.taskId) || !StringUtils.hasText(task.description)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", task.taskId.trim());
            item.put("description", task.description.trim());
            item.put("status", normalizeStatus(task.status));
            plan.add(item);
        }
        if (plan.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "plan.update");
        data.put("planId", planSnapshot.planId.trim());
        data.put("chatId", StringUtils.hasText(chatId) ? chatId : null);
        data.put("plan", plan);
        data.put("timestamp", normalizeEventTimestamp(previousTimestamp + 1, previousTimestamp));
        return data;
    }

    private String normalizeStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "init";
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "in_progress" -> "init";
            case "init", "completed", "failed", "canceled" -> normalized;
            default -> "init";
        };
    }

    private Map<String, Object> toRawMessageMap(String runId, ChatWindowMemoryStore.StoredMessage message) {
        if (message == null || !StringUtils.hasText(message.role)) {
            return Map.of();
        }
        Map<String, Object> root = objectMapper.convertValue(
                message,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
        );
        root.put("runId", runId);
        return root;
    }

    private record StepEntry(
            String stage,
            int seq,
            String taskId,
            long updatedAt,
            ChatWindowMemoryStore.SystemSnapshot system,
            ChatWindowMemoryStore.PlanSnapshot plan,
            List<ChatWindowMemoryStore.StoredMessage> messages,
            int lineIndex
    ) {
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + resolveSqlitePath());
    }

    private Path resolveSqlitePath() {
        ChatWindowMemoryProperties.IndexProperties index = properties.getIndex();
        String configured = index == null ? null : index.getSqliteFile();
        if (!StringUtils.hasText(configured)) {
            configured = "chats.db";
        }
        Path path = Paths.get(configured.trim());
        if (!path.isAbsolute()) {
            path = resolveBaseDir().resolve(path).normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private ChatIndexRecord findChatRecordById(Connection connection, String chatId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CHAT_ID_, CHAT_NAME_, AGENT_KEY_,
                       TEAM_ID_,
                       CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                FROM CHATS
                WHERE CHAT_ID_ = ?
                """)) {
            statement.setString(1, chatId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapChatIndexRecord(resultSet);
            }
        }
    }

    private ChatIndexRecord mapChatIndexRecord(ResultSet resultSet) throws SQLException {
        ChatIndexRecord record = new ChatIndexRecord();
        record.chatId = nullable(resultSet.getString("CHAT_ID_"));
        record.chatName = nullable(resultSet.getString("CHAT_NAME_"));
        record.agentKey = nullable(resultSet.getString("AGENT_KEY_"));
        record.teamId = nullable(resultSet.getString("TEAM_ID_"));
        record.createdAt = resultSet.getLong("CREATED_AT_");
        record.updatedAt = resultSet.getLong("UPDATED_AT_");
        record.lastRunId = nullable(resultSet.getString("LAST_RUN_ID_"));
        record.lastRunContent = nullable(resultSet.getString("LAST_RUN_CONTENT_"));
        record.readStatus = resultSet.getInt("READ_STATUS_");
        record.readAt = (Long) resultSet.getObject("READ_AT_");
        return record;
    }

    private void upsertChatIndex(Connection connection, ChatIndexRecord record) throws SQLException {
        validateChatBinding(record.agentKey, record.teamId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CHATS(
                    CHAT_ID_, CHAT_NAME_, AGENT_KEY_, TEAM_ID_,
                    CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(CHAT_ID_) DO UPDATE SET
                    CHAT_NAME_ = excluded.CHAT_NAME_,
                    AGENT_KEY_ = excluded.AGENT_KEY_,
                    TEAM_ID_ = excluded.TEAM_ID_,
                    CREATED_AT_ = excluded.CREATED_AT_,
                    UPDATED_AT_ = excluded.UPDATED_AT_,
                    LAST_RUN_ID_ = excluded.LAST_RUN_ID_,
                    LAST_RUN_CONTENT_ = excluded.LAST_RUN_CONTENT_,
                    READ_STATUS_ = excluded.READ_STATUS_,
                    READ_AT_ = excluded.READ_AT_
                """)) {
            statement.setString(1, record.chatId);
            statement.setString(2, StringUtils.hasText(record.chatName) ? record.chatName : record.chatId);
            statement.setString(3, StringUtils.hasText(record.agentKey) ? record.agentKey : "");
            statement.setObject(4, nullable(record.teamId));
            statement.setLong(5, record.createdAt > 0 ? record.createdAt : System.currentTimeMillis());
            statement.setLong(6, record.updatedAt > 0 ? record.updatedAt : System.currentTimeMillis());
            statement.setString(7, nullable(record.lastRunId) == null ? "" : record.lastRunId.trim());
            statement.setString(8, StringUtils.hasText(record.lastRunContent) ? record.lastRunContent : "");
            statement.setInt(9, record.readStatus == 0 ? 0 : 1);
            if (record.readAt == null) {
                statement.setObject(10, null);
            } else {
                statement.setLong(10, record.readAt);
            }
            statement.executeUpdate();
        }
    }

    private void validateChatBinding(String agentKey, String teamId) {
        boolean hasAgentKey = StringUtils.hasText(agentKey);
        boolean hasTeamId = StringUtils.hasText(teamId);
        if (hasAgentKey == hasTeamId) {
            throw new IllegalArgumentException("Exactly one of agentKey or teamId must be provided");
        }
    }

    private void validateBindingDrift(ChatIndexRecord record, String incomingAgentKey, String incomingTeamId) {
        String existingAgentKey = nullable(record.agentKey);
        String existingTeamId = nullable(record.teamId);
        if (!StringUtils.hasText(existingAgentKey) && !StringUtils.hasText(existingTeamId)) {
            return;
        }
        if (StringUtils.hasText(existingAgentKey)) {
            if (StringUtils.hasText(incomingTeamId)) {
                throw new IllegalArgumentException("chat binding mismatch: chat is already bound to agentKey");
            }
            if (StringUtils.hasText(incomingAgentKey) && !existingAgentKey.equals(incomingAgentKey)) {
                throw new IllegalArgumentException("chat binding mismatch: agentKey does not match existing binding");
            }
            return;
        }
        if (StringUtils.hasText(incomingAgentKey)) {
            throw new IllegalArgumentException("chat binding mismatch: chat is already bound to teamId");
        }
        if (StringUtils.hasText(incomingTeamId) && !existingTeamId.equals(incomingTeamId)) {
            throw new IllegalArgumentException("chat binding mismatch: teamId does not match existing binding");
        }
    }

    private void validateChatsSchema(Connection connection) throws SQLException {
        List<ColumnSchema> actualSchema = readTableSchema(connection, TABLE_CHATS);
        if (actualSchema.size() != CHATS_SCHEMA.size()) {
            throw incompatibleSchema("expected " + CHATS_SCHEMA.size() + " columns but found " + actualSchema.size());
        }
        for (int i = 0; i < CHATS_SCHEMA.size(); i++) {
            ColumnSchema expected = CHATS_SCHEMA.get(i);
            ColumnSchema actual = actualSchema.get(i);
            if (!expected.matches(actual)) {
                throw incompatibleSchema(
                        "column[" + i + "] expected " + expected.describe() + " but found " + actual.describe()
                );
            }
        }
    }

    private List<ColumnSchema> readTableSchema(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet resultSet = statement.executeQuery()) {
            List<ColumnSchema> columns = new ArrayList<>();
            while (resultSet.next()) {
                columns.add(ColumnSchema.fromPragmaRow(
                        resultSet.getString("name"),
                        resultSet.getString("type"),
                        resultSet.getInt("notnull"),
                        resultSet.getString("dflt_value"),
                        resultSet.getInt("pk")
                ));
            }
            return columns;
        }
    }

    private IncompatibleChatsSchemaException incompatibleSchema(String details) {
        return new IncompatibleChatsSchemaException(
                "Incompatible CHATS schema: " + details + ". Rebuild sqlite chat index at " + resolveSqlitePath()
        );
    }

    private Charset resolveCharset() {
        String configured = properties.getCharset();
        if (!StringUtils.hasText(configured)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(configured.trim());
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private long resolveCreatedAt(Path historyPath) {
        if (historyPath == null || !Files.exists(historyPath)) {
            return System.currentTimeMillis();
        }
        try {
            return Files.getLastModifiedTime(historyPath).toMillis();
        } catch (IOException ex) {
            return System.currentTimeMillis();
        }
    }

    private void requireValidChatId(String chatId) {
        if (!isValidChatId(chatId)) {
            throw new IllegalArgumentException("chatId must be a valid UUID");
        }
    }

    private boolean isValidChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return false;
        }
        try {
            UUID.fromString(chatId.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String deriveChatName(String message) {
        String normalized = StringUtils.hasText(message)
                ? message.trim().replaceAll("\\s+", " ")
                : "";
        if (normalized.isEmpty()) {
            return "新对话";
        }
        int[] codePoints = normalized.codePoints().limit(30).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private Path resolveBaseDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private Path resolveHistoryPath(String chatId) {
        return resolveBaseDir().resolve(chatId + ".json");
    }

    private JsonNode parseLine(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }

    private void appendJsonLine(Path path, Object value) {
        if (path == null || value == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            String line = objectMapper.writeValueAsString(value) + System.lineSeparator();
            Files.writeString(
                    path,
                    line,
                    resolveCharset(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (Exception ex) {
            log.warn("Cannot append chat event line path={}", path, ex);
        }
    }

    private boolean isPersistedEventType(String type) {
        return "request.submit".equals(type);
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private ChatSummary toChatSummary(ChatIndexRecord record) {
        return toChatSummary(record, false);
    }

    private ChatSummary toChatSummary(ChatIndexRecord record, boolean created) {
        long createdAt = record.createdAt > 0 ? record.createdAt : record.updatedAt;
        long updatedAt = record.updatedAt > 0 ? record.updatedAt : createdAt;
        return new ChatSummary(
                record.chatId,
                StringUtils.hasText(record.chatName) ? record.chatName : record.chatId,
                nullable(record.agentKey),
                nullable(record.teamId),
                createdAt,
                updatedAt,
                nullable(record.lastRunId) == null ? "" : record.lastRunId.trim(),
                StringUtils.hasText(record.lastRunContent) ? record.lastRunContent : "",
                record.readStatus == 0 ? 0 : 1,
                record.readAt,
                created
        );
    }

    private String nullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void putIfNonNull(Map<String, Object> node, String key, Object value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private String textOrFallback(Object value, String fallback) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return fallback;
    }

    public record ChatSummary(
            String chatId,
            String chatName,
            String agentKey,
            String teamId,
            long createdAt,
            long updatedAt,
            String lastRunId,
            String lastRunContent,
            int readStatus,
            Long readAt,
            boolean created
    ) {
    }

    public record RunCompletion(
            String chatId,
            String runId,
            String assistantContent,
            String fallbackUserMessage,
            long completedAt
    ) {
    }

    public record MarkChatReadResult(
            String chatId,
            int readStatus,
            Long readAt
    ) {
    }

    private static final class ParsedChatContent {
        private final List<RunSnapshot> runs = new ArrayList<>();
        private final List<Map<String, Object>> rawMessages = new ArrayList<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final LinkedHashMap<String, QueryRequest.Reference> references = new LinkedHashMap<>();
    }

    private record RunSnapshot(
            String runId,
            long updatedAt,
            Map<String, Object> query,
            ChatWindowMemoryStore.SystemSnapshot system,
            ChatWindowMemoryStore.PlanSnapshot plan,
            List<ChatWindowMemoryStore.StoredMessage> messages,
            List<PersistedEvent> persistedEvents,
            int lineIndex
    ) {
    }

    private record PersistedEvent(
            String type,
            long timestamp,
            Map<String, Object> payload,
            int lineIndex
    ) {
    }

    private record IdBinding(String id, boolean action) {
    }

    private record ColumnSchema(String name, String type, int notNull, String defaultValue, int pk) {
        private static ColumnSchema fromPragmaRow(String name, String type, int notNull, String defaultValue, int pk) {
            return new ColumnSchema(
                    normalizeName(name),
                    normalizeType(type),
                    notNull,
                    normalizeDefault(defaultValue),
                    pk
            );
        }

        private boolean matches(ColumnSchema other) {
            return name.equals(other.name)
                    && type.equals(other.type)
                    && notNull == other.notNull
                    && pk == other.pk
                    && Objects.equals(nullable(defaultValue), nullable(other.defaultValue));
        }

        private String describe() {
            return name + " " + type + " notNull=" + notNull + " default="
                    + (defaultValue == null ? "null" : defaultValue) + " pk=" + pk;
        }

        private static String normalizeName(String raw) {
            return raw == null ? "" : raw.trim().toUpperCase();
        }

        private static String normalizeType(String raw) {
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            return raw.trim().replaceAll("\\s+", "").toUpperCase();
        }

        private static String normalizeDefault(String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim();
            while (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() > 1) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
            if (normalized.isEmpty() || "NULL".equalsIgnoreCase(normalized)) {
                return null;
            }
            return normalized;
        }

        private static String nullable(String value) {
            return value == null ? null : value.trim();
        }
    }

    private static final class ChatIndexRecord {
        public String chatId;
        public String chatName;
        public String agentKey;
        public String teamId;
        public long createdAt;
        public long updatedAt;
        public String lastRunId;
        public String lastRunContent;
        public int readStatus;
        public Long readAt;
    }

    private static final class IncompatibleChatsSchemaException extends IllegalStateException {
        private IncompatibleChatsSchemaException(String message) {
            super(message);
        }
    }
}
