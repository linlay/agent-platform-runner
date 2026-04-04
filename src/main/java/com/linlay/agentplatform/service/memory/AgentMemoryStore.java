package com.linlay.agentplatform.service.memory;

import com.linlay.agentplatform.service.memory.AgentMemoryService;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.service.embedding.EmbeddingService;
import com.linlay.agentplatform.util.IdGenerators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryStore.class);
    private static final String RECENT_SORT = "recent";
    private static final String IMPORTANCE_SORT = "importance";
    private static final String MATCH_HYBRID = "hybrid";
    private static final String MATCH_FTS = "fts";
    private static final String MATCH_VECTOR = "vector";

    private final AgentMemoryProperties properties;
    private final AgentMemoryService agentMemoryService;
    private final EmbeddingService embeddingService;
    private final Map<String, Object> dbLocks = new ConcurrentHashMap<>();
    private final Set<String> initializedDatabases = ConcurrentHashMap.newKeySet();

    public AgentMemoryStore(
            AgentMemoryProperties properties,
            AgentMemoryService agentMemoryService,
            EmbeddingService embeddingService
    ) {
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
        this.agentMemoryService = agentMemoryService == null ? new AgentMemoryService() : agentMemoryService;
        this.embeddingService = embeddingService;
    }

    public record WriteRequest(
            String agentKey,
            String requestId,
            String chatId,
            String subjectKey,
            String summary,
            String sourceType,
            String category,
            int importance,
            List<String> tags
    ) {
    }

    public MemoryRecord write(
            String agentKey,
            Path agentDir,
            String content,
            String category,
            int importance,
            List<String> tags
    ) {
        return write(new WriteRequest(agentKey, null, null, null, content, "tool-write", category, importance, tags));
    }

    public MemoryRecord write(WriteRequest request) {
        String normalizedAgentKey = requireText(request == null ? null : request.agentKey(), "agentKey");
        String normalizedSummary = requireText(request == null ? null : request.summary(), "summary");
        String normalizedSubjectKey = normalizeSubjectKey(
                request == null ? null : request.subjectKey(),
                request == null ? null : request.chatId(),
                normalizedAgentKey
        );
        String normalizedSourceType = normalizeSourceType(request == null ? null : request.sourceType());
        String normalizedCategory = normalizeCategory(request == null ? null : request.category());
        int normalizedImportance = normalizeImportance(request == null ? 0 : request.importance());
        List<String> normalizedTags = normalizeTags(request == null ? null : request.tags());
        String normalizedRequestId = normalizeNullable(request == null ? null : request.requestId());
        String normalizedChatId = normalizeNullable(request == null ? null : request.chatId());
        String id = IdGenerators.shortHexId("mem");
        long now = System.currentTimeMillis();
        Path dbPath = resolveDbPath();
        String embeddingModel = normalizeNullable(properties.getEmbeddingModel());
        Optional<float[]> embedding = safeEmbed(normalizedSummary);

        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO MEMORIES (
                           ID_, TS_, REQUEST_ID_, CHAT_ID_, AGENT_KEY_, SUBJECT_KEY_, SOURCE_TYPE_,
                           SUMMARY_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_, EMBEDDING_MODEL_,
                           UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, id);
                statement.setLong(2, now);
                setNullableText(statement, 3, normalizedRequestId);
                setNullableText(statement, 4, normalizedChatId);
                statement.setString(5, normalizedAgentKey);
                statement.setString(6, normalizedSubjectKey);
                statement.setString(7, normalizedSourceType);
                statement.setString(8, normalizedSummary);
                statement.setString(9, normalizedCategory);
                statement.setInt(10, normalizedImportance);
                statement.setString(11, joinTags(normalizedTags));
                if (embedding.isPresent()) {
                    statement.setBytes(12, serializeEmbedding(embedding.get()));
                } else {
                    statement.setNull(12, java.sql.Types.BLOB);
                }
                setNullableText(statement, 13, embedding.isPresent() ? embeddingModel : null);
                statement.setLong(14, now);
                statement.setInt(15, 0);
                statement.setNull(16, java.sql.Types.BIGINT);
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot write memory for agentKey=" + normalizedAgentKey, ex);
            }
        }

        agentMemoryService.appendJournalEntry(
                id,
                Instant.ofEpochMilli(now),
                normalizedRequestId,
                normalizedChatId,
                normalizedAgentKey,
                normalizedSubjectKey,
                normalizedSummary,
                normalizedSourceType,
                normalizedCategory,
                normalizedImportance,
                normalizedTags
        );

        return new MemoryRecord(
                id,
                normalizedAgentKey,
                normalizedSubjectKey,
                normalizedSummary,
                normalizedSourceType,
                normalizedCategory,
                normalizedImportance,
                normalizedTags,
                embedding.isPresent(),
                embedding.isPresent() ? embeddingModel : null,
                now,
                now,
                0,
                null
        );
    }

    public Optional<MemoryRecord> read(String agentKey, Path agentDir, String id) {
        String normalizedId = requireText(id, "id");
        Path dbPath = resolveDbPath();
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT ID_, AGENT_KEY_, SUBJECT_KEY_, SUMMARY_, SOURCE_TYPE_, CATEGORY_,
                                IMPORTANCE_, TAGS_, EMBEDDING_, EMBEDDING_MODEL_, TS_, UPDATED_AT_,
                                ACCESS_COUNT_, LAST_ACCESSED_AT_
                         FROM MEMORIES
                         WHERE AGENT_KEY_ = ? AND ID_ = ?
                         """)) {
                statement.setString(1, requireText(agentKey, "agentKey"));
                statement.setString(2, normalizedId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    MemoryRecord record = mapRecord(resultSet);
                    touch(connection, List.of(record.id()));
                    return Optional.of(withTouched(record));
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot read memory id=" + normalizedId, ex);
            }
        }
    }

    public List<MemoryRecord> list(String agentKey, Path agentDir, String category, int limit, String sortBy) {
        int normalizedLimit = normalizeLimit(limit);
        if (normalizedLimit <= 0) {
            return List.of();
        }
        String normalizedSort = normalizeSort(sortBy);
        String normalizedCategory = normalizeOptionalCategory(category);
        Path dbPath = resolveDbPath();
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            String sql = """
                    SELECT ID_, AGENT_KEY_, SUBJECT_KEY_, SUMMARY_, SOURCE_TYPE_, CATEGORY_,
                           IMPORTANCE_, TAGS_, EMBEDDING_, EMBEDDING_MODEL_, TS_, UPDATED_AT_,
                           ACCESS_COUNT_, LAST_ACCESSED_AT_
                    FROM MEMORIES
                    WHERE AGENT_KEY_ = ?
                    """
                    + (normalizedCategory == null ? "" : " AND CATEGORY_ = ?")
                    + ("importance".equals(normalizedSort)
                    ? " ORDER BY IMPORTANCE_ DESC, UPDATED_AT_ DESC LIMIT ?"
                    : " ORDER BY UPDATED_AT_ DESC, TS_ DESC LIMIT ?");
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, requireText(agentKey, "agentKey"));
                int parameterIndex = 2;
                if (normalizedCategory != null) {
                    statement.setString(parameterIndex++, normalizedCategory);
                }
                statement.setInt(parameterIndex, normalizedLimit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return readRecords(resultSet);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot list memories for agentKey=" + agentKey, ex);
            }
        }
    }

    public List<ScoredMemory> search(String agentKey, Path agentDir, String query, String category, int limit) {
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : "";
        int normalizedLimit = normalizeLimit(limit);
        if (!StringUtils.hasText(normalizedQuery) || normalizedLimit <= 0) {
            return List.of();
        }
        String normalizedAgentKey = requireText(agentKey, "agentKey");
        String normalizedCategory = normalizeOptionalCategory(category);
        Path dbPath = resolveDbPath();
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath)) {
                Map<String, CandidateScore> ftsScores = ftsCandidates(connection, normalizedAgentKey, normalizedCategory, normalizedQuery, normalizedLimit * 3);
                Map<String, CandidateScore> vectorScores = vectorCandidates(connection, normalizedAgentKey, normalizedCategory, normalizedQuery, normalizedLimit * 3);

                if (ftsScores.isEmpty() && vectorScores.isEmpty()) {
                    return List.of();
                }

                Map<String, Double> normalizedFtsScores = normalizeScores(ftsScores);
                Map<String, Double> normalizedVectorScores = normalizeScores(vectorScores);
                Set<String> candidateIds = new LinkedHashSet<>();
                candidateIds.addAll(ftsScores.keySet());
                candidateIds.addAll(vectorScores.keySet());

                List<ScoredMemory> results = new ArrayList<>();
                for (String candidateId : candidateIds) {
                    MemoryRecord record = Optional.ofNullable(ftsScores.get(candidateId))
                            .map(CandidateScore::record)
                            .orElseGet(() -> Optional.ofNullable(vectorScores.get(candidateId))
                                    .map(CandidateScore::record)
                                    .orElse(null));
                    if (record == null) {
                        continue;
                    }
                    boolean hasFts = normalizedFtsScores.containsKey(candidateId);
                    boolean hasVector = normalizedVectorScores.containsKey(candidateId);
                    double score;
                    String matchType;
                    if (hasFts && hasVector) {
                        score = properties.getHybridVectorWeight() * normalizedVectorScores.get(candidateId)
                                + properties.getHybridFtsWeight() * normalizedFtsScores.get(candidateId);
                        matchType = MATCH_HYBRID;
                    } else if (hasVector) {
                        score = normalizedVectorScores.get(candidateId);
                        matchType = MATCH_VECTOR;
                    } else {
                        score = normalizedFtsScores.getOrDefault(candidateId, 0d);
                        matchType = MATCH_FTS;
                    }
                    results.add(new ScoredMemory(record, score, matchType));
                }

                results.sort(Comparator
                        .comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(value -> value.memory().importance(), Comparator.reverseOrder())
                        .thenComparing(value -> value.memory().updatedAt(), Comparator.reverseOrder()));
                if (results.size() > normalizedLimit) {
                    results = new ArrayList<>(results.subList(0, normalizedLimit));
                }
                touch(connection, results.stream().map(result -> result.memory().id()).toList());
                return results.stream()
                        .map(result -> new ScoredMemory(withTouched(result.memory()), result.score(), result.matchType()))
                        .toList();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot search memories for agentKey=" + normalizedAgentKey, ex);
            }
        }
    }

    public boolean delete(String agentKey, Path agentDir, String id) {
        String normalizedAgentKey = requireText(agentKey, "agentKey");
        String normalizedId = requireText(id, "id");
        Path dbPath = resolveDbPath();
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement("""
                         DELETE FROM MEMORIES
                         WHERE AGENT_KEY_ = ? AND ID_ = ?
                         """)) {
                statement.setString(1, normalizedAgentKey);
                statement.setString(2, normalizedId);
                return statement.executeUpdate() > 0;
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot delete memory id=" + normalizedId, ex);
            }
        }
    }

    public List<MemoryRecord> topRelevant(String agentKey, Path agentDir, String query, int topN) {
        if (!StringUtils.hasText(query)) {
            return list(agentKey, agentDir, null, topN, IMPORTANCE_SORT);
        }
        return search(agentKey, agentDir, query, null, topN).stream()
                .map(ScoredMemory::memory)
                .toList();
    }

    private Map<String, CandidateScore> ftsCandidates(
            Connection connection,
            String agentKey,
            String category,
            String query,
            int limit
    ) throws SQLException {
        String ftsQuery = buildFtsQuery(query);
        if (!StringUtils.hasText(ftsQuery)) {
            return Map.of();
        }
        String sql = """
                SELECT m.ID_, m.AGENT_KEY_, m.SUBJECT_KEY_, m.SUMMARY_, m.SOURCE_TYPE_, m.CATEGORY_,
                       m.IMPORTANCE_, m.TAGS_, m.EMBEDDING_, m.EMBEDDING_MODEL_, m.TS_, m.UPDATED_AT_,
                       m.ACCESS_COUNT_, m.LAST_ACCESSED_AT_,
                       -bm25(MEMORIES_FTS) AS SCORE_
                FROM MEMORIES_FTS
                JOIN MEMORIES m ON m.rowid = MEMORIES_FTS.rowid
                WHERE m.AGENT_KEY_ = ?
                  AND MEMORIES_FTS MATCH ?
                """
                + (category == null ? "" : " AND m.CATEGORY_ = ?")
                + " ORDER BY bm25(MEMORIES_FTS) LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentKey);
            statement.setString(2, ftsQuery);
            int parameterIndex = 3;
            if (category != null) {
                statement.setString(parameterIndex++, category);
            }
            statement.setInt(parameterIndex, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                return readScoredRecords(resultSet);
            }
        } catch (SQLException ex) {
            log.debug("FTS query failed, fallback to LIKE search for query={}", query, ex);
            return likeCandidates(connection, agentKey, category, query, limit);
        }
    }

    private Map<String, CandidateScore> likeCandidates(
            Connection connection,
            String agentKey,
            String category,
            String query,
            int limit
    ) throws SQLException {
        String sql = """
                SELECT ID_, AGENT_KEY_, SUBJECT_KEY_, SUMMARY_, SOURCE_TYPE_, CATEGORY_,
                       IMPORTANCE_, TAGS_, EMBEDDING_, EMBEDDING_MODEL_, TS_, UPDATED_AT_,
                       ACCESS_COUNT_, LAST_ACCESSED_AT_
                FROM MEMORIES
                WHERE AGENT_KEY_ = ?
                  AND (SUMMARY_ LIKE ? OR SUBJECT_KEY_ LIKE ? OR CATEGORY_ LIKE ? OR TAGS_ LIKE ?)
                """
                + (category == null ? "" : " AND CATEGORY_ = ?")
                + " ORDER BY IMPORTANCE_ DESC, UPDATED_AT_ DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String likeQuery = "%" + query.trim() + "%";
            statement.setString(1, agentKey);
            statement.setString(2, likeQuery);
            statement.setString(3, likeQuery);
            statement.setString(4, likeQuery);
            statement.setString(5, likeQuery);
            int parameterIndex = 6;
            if (category != null) {
                statement.setString(parameterIndex++, category);
            }
            statement.setInt(parameterIndex, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, CandidateScore> results = new LinkedHashMap<>();
                double rank = Math.max(1, limit);
                while (resultSet.next()) {
                    MemoryRecord record = mapRecord(resultSet);
                    results.put(record.id(), new CandidateScore(record, rank--));
                }
                return results;
            }
        }
    }

    private Map<String, CandidateScore> vectorCandidates(
            Connection connection,
            String agentKey,
            String category,
            String query,
            int limit
    ) throws SQLException {
        if (embeddingService == null) {
            return Map.of();
        }
        Optional<float[]> queryEmbedding = safeEmbed(query);
        if (queryEmbedding.isEmpty()) {
            return Map.of();
        }
        float[] queryVector = queryEmbedding.get();
        String sql = """
                SELECT ID_, AGENT_KEY_, SUBJECT_KEY_, SUMMARY_, SOURCE_TYPE_, CATEGORY_,
                       IMPORTANCE_, TAGS_, EMBEDDING_, EMBEDDING_MODEL_, TS_, UPDATED_AT_,
                       ACCESS_COUNT_, LAST_ACCESSED_AT_
                FROM MEMORIES
                WHERE AGENT_KEY_ = ?
                  AND EMBEDDING_ IS NOT NULL
                """
                + (category == null ? "" : " AND CATEGORY_ = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentKey);
            if (category != null) {
                statement.setString(2, category);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CandidateScore> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    MemoryRecord record = mapRecord(resultSet);
                    byte[] bytes = resultSet.getBytes("EMBEDDING_");
                    float[] storedVector = deserializeEmbedding(bytes);
                    if (storedVector == null || storedVector.length != queryVector.length) {
                        continue;
                    }
                    double cosine = cosineSimilarity(queryVector, storedVector);
                    candidates.add(new CandidateScore(record, cosine));
                }
                return candidates.stream()
                        .sorted(Comparator.comparingDouble(CandidateScore::score).reversed())
                        .limit(Math.max(1, limit))
                        .collect(LinkedHashMap::new, (map, candidate) -> map.put(candidate.record().id(), candidate), Map::putAll);
            }
        }
    }

    private void ensureInitialized(Path dbPath) {
        String key = dbPath.toAbsolutePath().normalize().toString();
        if (!Files.isRegularFile(dbPath)) {
            initializedDatabases.remove(key);
        }
        if (initializedDatabases.contains(key)) {
            return;
        }
        synchronized (lockFor(dbPath)) {
            if (initializedDatabases.contains(key)) {
                return;
            }
            boolean newDatabase = !Files.isRegularFile(dbPath);
            Path parent = dbPath.getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot create memory db directory: " + dbPath, ex);
            }
            try (Connection connection = openConnection(dbPath); Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS MEMORIES (
                          ID_ TEXT PRIMARY KEY,
                          TS_ INTEGER NOT NULL,
                          REQUEST_ID_ TEXT,
                          CHAT_ID_ TEXT,
                          AGENT_KEY_ TEXT NOT NULL,
                          SUBJECT_KEY_ TEXT NOT NULL,
                          SOURCE_TYPE_ TEXT NOT NULL,
                          SUMMARY_ TEXT NOT NULL,
                          CATEGORY_ TEXT DEFAULT 'general',
                          IMPORTANCE_ INTEGER DEFAULT 5,
                          TAGS_ TEXT,
                          EMBEDDING_ BLOB,
                          EMBEDDING_MODEL_ TEXT,
                          UPDATED_AT_ INTEGER NOT NULL,
                          ACCESS_COUNT_ INTEGER DEFAULT 0,
                          LAST_ACCESSED_AT_ INTEGER
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_AGENT_KEY_ ON MEMORIES(AGENT_KEY_)");
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_SUBJECT_KEY_ ON MEMORIES(SUBJECT_KEY_)");
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_CHAT_ID_ ON MEMORIES(CHAT_ID_)");
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_TS_ ON MEMORIES(TS_ DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_IMPORTANCE_ ON MEMORIES(IMPORTANCE_ DESC)");
                statement.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS MEMORIES_FTS USING fts5(
                          SUMMARY_, SUBJECT_KEY_, CATEGORY_, TAGS_,
                          content=MEMORIES, content_rowid=rowid
                        )
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AI AFTER INSERT ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(rowid, SUMMARY_, SUBJECT_KEY_, CATEGORY_, TAGS_)
                          VALUES (new.rowid, new.SUMMARY_, new.SUBJECT_KEY_, new.CATEGORY_, new.TAGS_);
                        END
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AU AFTER UPDATE ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(MEMORIES_FTS, rowid, SUMMARY_, SUBJECT_KEY_, CATEGORY_, TAGS_)
                          VALUES ('delete', old.rowid, old.SUMMARY_, old.SUBJECT_KEY_, old.CATEGORY_, old.TAGS_);
                          INSERT INTO MEMORIES_FTS(rowid, SUMMARY_, SUBJECT_KEY_, CATEGORY_, TAGS_)
                          VALUES (new.rowid, new.SUMMARY_, new.SUBJECT_KEY_, new.CATEGORY_, new.TAGS_);
                        END
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AD AFTER DELETE ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(MEMORIES_FTS, rowid, SUMMARY_, SUBJECT_KEY_, CATEGORY_, TAGS_)
                          VALUES ('delete', old.rowid, old.SUMMARY_, old.SUBJECT_KEY_, old.CATEGORY_, old.TAGS_);
                        END
                        """);
                statement.execute("INSERT INTO MEMORIES_FTS(MEMORIES_FTS) VALUES('rebuild')");
                if (newDatabase || tableRowCount(connection) == 0) {
                    rebuildFromJournal(connection);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot initialize memory database: " + dbPath, ex);
            }
            initializedDatabases.add(key);
        }
    }

    private void rebuildFromJournal(Connection connection) {
        log.info(
                "Memory journal replay skipped: journal is now a human-readable log only, database path={}",
                resolveDbPath()
        );
    }

    private Connection openConnection(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().normalize());
    }

    private Path resolveDbPath() {
        return agentMemoryService.resolveMemoryDbPath();
    }

    private Object lockFor(Path dbPath) {
        String key = dbPath.toAbsolutePath().normalize().toString();
        return dbLocks.computeIfAbsent(key, ignored -> new Object());
    }

    private MemoryRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new MemoryRecord(
                resultSet.getString("ID_"),
                resultSet.getString("AGENT_KEY_"),
                resultSet.getString("SUBJECT_KEY_"),
                resultSet.getString("SUMMARY_"),
                resultSet.getString("SOURCE_TYPE_"),
                resultSet.getString("CATEGORY_"),
                resultSet.getInt("IMPORTANCE_"),
                splitTags(resultSet.getString("TAGS_")),
                resultSet.getBytes("EMBEDDING_") != null,
                resultSet.getString("EMBEDDING_MODEL_"),
                resultSet.getLong("TS_"),
                resultSet.getLong("UPDATED_AT_"),
                resultSet.getInt("ACCESS_COUNT_"),
                nullableLong(resultSet, "LAST_ACCESSED_AT_")
        );
    }

    private List<MemoryRecord> readRecords(ResultSet resultSet) throws SQLException {
        List<MemoryRecord> records = new ArrayList<>();
        while (resultSet.next()) {
            records.add(mapRecord(resultSet));
        }
        return List.copyOf(records);
    }

    private Map<String, CandidateScore> readScoredRecords(ResultSet resultSet) throws SQLException {
        Map<String, CandidateScore> results = new LinkedHashMap<>();
        while (resultSet.next()) {
            MemoryRecord record = mapRecord(resultSet);
            results.put(record.id(), new CandidateScore(record, resultSet.getDouble("SCORE_")));
        }
        return results;
    }

    private void touch(Connection connection, Collection<String> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> normalizedIds = ids.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String placeholders = String.join(",", java.util.Collections.nCopies(normalizedIds.size(), "?"));
        String sql = "UPDATE MEMORIES SET ACCESS_COUNT_ = ACCESS_COUNT_ + 1, LAST_ACCESSED_AT_ = ?, UPDATED_AT_ = ? WHERE ID_ IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            for (int i = 0; i < normalizedIds.size(); i++) {
                statement.setString(i + 3, normalizedIds.get(i));
            }
            statement.executeUpdate();
        }
    }

    private MemoryRecord withTouched(MemoryRecord record) {
        long now = System.currentTimeMillis();
        return new MemoryRecord(
                record.id(),
                record.agentKey(),
                record.subjectKey(),
                record.content(),
                record.sourceType(),
                record.category(),
                record.importance(),
                record.tags(),
                record.hasEmbedding(),
                record.embeddingModel(),
                record.createdAt(),
                now,
                record.accessCount() + 1,
                now
        );
    }

    private Optional<float[]> safeEmbed(String text) {
        if (embeddingService == null) {
            return Optional.empty();
        }
        try {
            return embeddingService.embed(text);
        } catch (Exception ex) {
            log.debug("Embedding generation failed, fallback to FTS-only memory search", ex);
            return Optional.empty();
        }
    }

    private int tableRowCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(1) FROM MEMORIES");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private void setNullableText(PreparedStatement statement, int index, String value) throws SQLException {
        if (StringUtils.hasText(value)) {
            statement.setString(index, value.trim());
        } else {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
    }

    private String buildFtsQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        for (String token : query.trim().split("\\s+")) {
            String normalized = token == null ? "" : token.trim().replace("\"", "");
            if (normalized.length() < 2) {
                continue;
            }
            terms.add("\"" + normalized + "\"");
        }
        if (terms.isEmpty()) {
            return null;
        }
        return String.join(" AND ", terms);
    }

    private Map<String, Double> normalizeScores(Map<String, CandidateScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }
        double max = scores.values().stream()
                .mapToDouble(CandidateScore::score)
                .max()
                .orElse(0d);
        double min = scores.values().stream()
                .mapToDouble(CandidateScore::score)
                .min()
                .orElse(0d);
        if (Double.compare(max, min) == 0) {
            return scores.keySet().stream()
                    .collect(LinkedHashMap::new, (map, key) -> map.put(key, 1d), Map::putAll);
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, CandidateScore> entry : scores.entrySet()) {
            normalized.put(entry.getKey(), (entry.getValue().score() - min) / (max - min));
        }
        return Map.copyOf(normalized);
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0d;
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private byte[] serializeEmbedding(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] deserializeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : "general";
    }

    private String normalizeOptionalCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String normalizeSort(String sortBy) {
        String normalized = normalizeNullable(sortBy);
        if (!StringUtils.hasText(normalized)) {
            return RECENT_SORT;
        }
        return IMPORTANCE_SORT.equalsIgnoreCase(normalized) ? IMPORTANCE_SORT : RECENT_SORT;
    }

    private String normalizeSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType.trim().toLowerCase(Locale.ROOT) : "tool-write";
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return properties.getSearchDefaultLimit();
        }
        return Math.min(limit, 100);
    }

    private int normalizeImportance(int importance) {
        int normalized = importance <= 0 ? 5 : importance;
        return Math.max(1, Math.min(10, normalized));
    }

    private String normalizeSubjectKey(String subjectKey, String chatId, String agentKey) {
        if (StringUtils.hasText(subjectKey)) {
            return subjectKey.trim();
        }
        if (StringUtils.hasText(chatId)) {
            return "chat:" + chatId.trim();
        }
        if (StringUtils.hasText(agentKey)) {
            return "agent:" + agentKey.trim();
        }
        return "_global";
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }

    private List<String> splitTags(String joined) {
        if (!StringUtils.hasText(joined)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String token : joined.split(",")) {
            if (StringUtils.hasText(token)) {
                tags.add(token.trim());
            }
        }
        return List.copyOf(tags);
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record CandidateScore(MemoryRecord record, double score) {
        private CandidateScore {
            Objects.requireNonNull(record, "record");
        }
    }
}
