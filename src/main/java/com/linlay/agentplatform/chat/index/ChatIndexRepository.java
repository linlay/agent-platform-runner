package com.linlay.agentplatform.chat.index;

import com.linlay.agentplatform.chat.history.ChatRecordStore;
import com.linlay.agentplatform.config.properties.ChatStorageProperties;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ChatIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatIndexRepository.class);
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

    private final ChatStorageProperties properties;
    private final Object lock;

    public ChatIndexRepository(ChatStorageProperties properties, Object lock) {
        this.properties = properties;
        this.lock = lock;
    }

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

    public ChatRecordStore.ChatSummary ensureChat(
            String chatId,
            String firstAgentKey,
            String firstAgentName,
            String firstTeamId,
            String firstMessage
    ) {
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

    public void onRunCompleted(ChatRecordStore.RunCompletion completion) {
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

    public List<ChatSummaryResponse> listChats(String lastRunId, String agentKey) {
        boolean incremental = StringUtils.hasText(lastRunId);
        boolean agentFiltered = StringUtils.hasText(agentKey);
        StringBuilder sql = new StringBuilder("""
                SELECT CHAT_ID_, CHAT_NAME_, AGENT_KEY_, TEAM_ID_,
                       CREATED_AT_, UPDATED_AT_, LAST_RUN_ID_, LAST_RUN_CONTENT_, READ_STATUS_, READ_AT_
                FROM CHATS
                """);
        List<String> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (incremental) {
            conditions.add("LAST_RUN_ID_ > ?");
            params.add(lastRunId.trim());
        }
        if (agentFiltered) {
            conditions.add("AGENT_KEY_ = ?");
            params.add(agentKey.trim());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (incremental) {
            sql.append(" ORDER BY LAST_RUN_ID_ ASC");
        } else {
            sql.append(" ORDER BY LAST_RUN_ID_ DESC, UPDATED_AT_ DESC");
        }
        synchronized (lock) {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < params.size(); index++) {
                    statement.setString(index + 1, params.get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<ChatSummaryResponse> responses = new ArrayList<>();
                    while (resultSet.next()) {
                        ChatIndexRecord record = mapChatIndexRecord(resultSet);
                        ChatRecordStore.ChatSummary summary = toChatSummary(record, false);
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

    public ChatRecordStore.MarkChatReadResult markChatRead(String chatId) {
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
                return new ChatRecordStore.MarkChatReadResult(chatId, 1, readAt);
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot mark chat as read for " + chatId, ex);
            }
        }
    }

    public ChatIndexRecord loadChatRecord(String chatId) {
        requireValidChatId(chatId);
        synchronized (lock) {
            try (Connection connection = openConnection()) {
                return findChatRecordById(connection, chatId);
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot query chat index for chatId=" + chatId, ex);
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
            candidate = parent == null
                    ? Path.of(baseName + "." + suffix)
                    : parent.resolve(baseName + "." + suffix);
            suffix++;
        }
        return candidate;
    }

    private boolean isAutoRebuildOnIncompatibleSchema() {
        ChatStorageProperties.IndexProperties index = properties.getIndex();
        return index == null || index.isAutoRebuildOnIncompatibleSchema();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + resolveSqlitePath());
    }

    private Path resolveSqlitePath() {
        ChatStorageProperties.IndexProperties index = properties.getIndex();
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
        if (hasAgentKey && hasTeamId) {
            throw new IllegalArgumentException("At most one of agentKey or teamId may be provided");
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

    private void requireValidChatId(String chatId) {
        if (!isValidChatId(chatId)) {
            throw new IllegalArgumentException("chatId must be a valid UUID");
        }
    }

    private boolean isValidChatId(String chatId) {
        return StringHelpers.isValidChatId(chatId);
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

    private ChatRecordStore.ChatSummary toChatSummary(ChatIndexRecord record, boolean created) {
        long createdAt = record.createdAt > 0 ? record.createdAt : record.updatedAt;
        long updatedAt = record.updatedAt > 0 ? record.updatedAt : createdAt;
        return new ChatRecordStore.ChatSummary(
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
        return StringHelpers.nullable(value);
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
            return StringHelpers.nullable(value);
        }
    }

    private static final class IncompatibleChatsSchemaException extends IllegalStateException {
        private IncompatibleChatsSchemaException(String message) {
            super(message);
        }
    }
}
