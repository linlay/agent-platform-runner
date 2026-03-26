package com.linlay.agentplatform.service.memory;

import com.linlay.agentplatform.agent.AgentProperties;
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
    private final AgentProperties agentProperties;
    private final EmbeddingService embeddingService;
    private final Map<String, Object> dbLocks = new ConcurrentHashMap<>();
    private final Set<String> initializedDatabases = ConcurrentHashMap.newKeySet();

    public AgentMemoryStore(
            AgentMemoryProperties properties,
            AgentProperties agentProperties,
            EmbeddingService embeddingService
    ) {
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
        this.agentProperties = agentProperties == null ? new AgentProperties() : agentProperties;
        this.embeddingService = embeddingService;
    }

    public MemoryRecord write(
            String agentKey,
            Path agentDir,
            String content,
            String category,
            int importance,
            List<String> tags
    ) {
        String normalizedAgentKey = requireText(agentKey, "agentKey");
        String normalizedContent = requireText(content, "content");
        String normalizedCategory = normalizeCategory(category);
        int normalizedImportance = normalizeImportance(importance);
        List<String> normalizedTags = normalizeTags(tags);
        long now = System.currentTimeMillis();
        String id = IdGenerators.shortHexId("mem");
        Path dbPath = resolveDbPath(normalizedAgentKey, agentDir);
        Optional<float[]> embedding = safeEmbed(normalizedContent);

        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO MEMORIES (
                           ID_, AGENT_KEY_, CONTENT_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_,
                           CREATED_AT_, UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, id);
                statement.setString(2, normalizedAgentKey);
                statement.setString(3, normalizedContent);
                statement.setString(4, normalizedCategory);
                statement.setInt(5, normalizedImportance);
                statement.setString(6, joinTags(normalizedTags));
                if (embedding.isPresent()) {
                    statement.setBytes(7, serializeEmbedding(embedding.get()));
                } else {
                    statement.setNull(7, java.sql.Types.BLOB);
                }
                statement.setLong(8, now);
                statement.setLong(9, now);
                statement.setInt(10, 0);
                statement.setNull(11, java.sql.Types.BIGINT);
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot write agent memory for agentKey=" + normalizedAgentKey, ex);
            }
        }

        return new MemoryRecord(
                id,
                normalizedAgentKey,
                normalizedContent,
                normalizedCategory,
                normalizedImportance,
                normalizedTags,
                embedding.isPresent(),
                now,
                now,
                0,
                null
        );
    }

    public Optional<MemoryRecord> read(String agentKey, Path agentDir, String id) {
        String normalizedId = requireText(id, "id");
        Path dbPath = resolveDbPath(agentKey, agentDir);
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            try (Connection connection = openConnection(dbPath);
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT ID_, AGENT_KEY_, CONTENT_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_,
                                CREATED_AT_, UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
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
                throw new IllegalStateException("Cannot read agent memory id=" + normalizedId, ex);
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
        Path dbPath = resolveDbPath(agentKey, agentDir);
        synchronized (lockFor(dbPath)) {
            ensureInitialized(dbPath);
            String sql = """
                    SELECT ID_, AGENT_KEY_, CONTENT_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_,
                           CREATED_AT_, UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
                    FROM MEMORIES
                    WHERE AGENT_KEY_ = ?
                    """
                    + (normalizedCategory == null ? "" : " AND CATEGORY_ = ?")
                    + ("importance".equals(normalizedSort)
                    ? " ORDER BY IMPORTANCE_ DESC, UPDATED_AT_ DESC LIMIT ?"
                    : " ORDER BY UPDATED_AT_ DESC, CREATED_AT_ DESC LIMIT ?");
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
        Path dbPath = resolveDbPath(normalizedAgentKey, agentDir);
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
        Path dbPath = resolveDbPath(normalizedAgentKey, agentDir);
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
                SELECT m.ID_, m.AGENT_KEY_, m.CONTENT_, m.CATEGORY_, m.IMPORTANCE_, m.TAGS_, m.EMBEDDING_,
                       m.CREATED_AT_, m.UPDATED_AT_, m.ACCESS_COUNT_, m.LAST_ACCESSED_AT_,
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
                return readScoredRecords(resultSet, MATCH_FTS);
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
                SELECT ID_, AGENT_KEY_, CONTENT_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_,
                       CREATED_AT_, UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
                FROM MEMORIES
                WHERE AGENT_KEY_ = ?
                  AND (CONTENT_ LIKE ? OR CATEGORY_ LIKE ? OR TAGS_ LIKE ?)
                """
                + (category == null ? "" : " AND CATEGORY_ = ?")
                + " ORDER BY IMPORTANCE_ DESC, UPDATED_AT_ DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String likeQuery = "%" + query.trim() + "%";
            statement.setString(1, agentKey);
            statement.setString(2, likeQuery);
            statement.setString(3, likeQuery);
            statement.setString(4, likeQuery);
            int parameterIndex = 5;
            if (category != null) {
                statement.setString(parameterIndex++, category);
            }
            statement.setInt(parameterIndex, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, CandidateScore> results = new LinkedHashMap<>();
                double rank = Math.max(1, limit);
                while (resultSet.next()) {
                    MemoryRecord record = mapRecord(resultSet);
                    results.put(record.id(), new CandidateScore(record, rank--, MATCH_FTS));
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
                SELECT ID_, AGENT_KEY_, CONTENT_, CATEGORY_, IMPORTANCE_, TAGS_, EMBEDDING_,
                       CREATED_AT_, UPDATED_AT_, ACCESS_COUNT_, LAST_ACCESSED_AT_
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
                    candidates.add(new CandidateScore(record, cosine, MATCH_VECTOR));
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
        if (initializedDatabases.contains(key)) {
            return;
        }
        synchronized (lockFor(dbPath)) {
            if (initializedDatabases.contains(key)) {
                return;
            }
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
                          AGENT_KEY_ TEXT NOT NULL,
                          CONTENT_ TEXT NOT NULL,
                          CATEGORY_ TEXT DEFAULT 'general',
                          IMPORTANCE_ INTEGER DEFAULT 5,
                          TAGS_ TEXT,
                          EMBEDDING_ BLOB,
                          CREATED_AT_ INTEGER NOT NULL,
                          UPDATED_AT_ INTEGER NOT NULL,
                          ACCESS_COUNT_ INTEGER DEFAULT 0,
                          LAST_ACCESSED_AT_ INTEGER
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_AGENT_KEY_ ON MEMORIES(AGENT_KEY_)");
                statement.execute("CREATE INDEX IF NOT EXISTS IDX_MEMORIES_IMPORTANCE_ ON MEMORIES(IMPORTANCE_ DESC)");
                statement.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS MEMORIES_FTS USING fts5(
                          CONTENT_, CATEGORY_, TAGS_,
                          content=MEMORIES, content_rowid=rowid
                        )
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AI AFTER INSERT ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(rowid, CONTENT_, CATEGORY_, TAGS_)
                          VALUES (new.rowid, new.CONTENT_, new.CATEGORY_, new.TAGS_);
                        END
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AU AFTER UPDATE ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(MEMORIES_FTS, rowid, CONTENT_, CATEGORY_, TAGS_)
                          VALUES ('delete', old.rowid, old.CONTENT_, old.CATEGORY_, old.TAGS_);
                          INSERT INTO MEMORIES_FTS(rowid, CONTENT_, CATEGORY_, TAGS_)
                          VALUES (new.rowid, new.CONTENT_, new.CATEGORY_, new.TAGS_);
                        END
                        """);
                statement.execute("""
                        CREATE TRIGGER IF NOT EXISTS MEMORIES_AD AFTER DELETE ON MEMORIES BEGIN
                          INSERT INTO MEMORIES_FTS(MEMORIES_FTS, rowid, CONTENT_, CATEGORY_, TAGS_)
                          VALUES ('delete', old.rowid, old.CONTENT_, old.CATEGORY_, old.TAGS_);
                        END
                        """);
                statement.execute("INSERT INTO MEMORIES_FTS(MEMORIES_FTS) VALUES('rebuild')");
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot initialize memory database: " + dbPath, ex);
            }
            initializedDatabases.add(key);
        }
    }

    private Connection openConnection(Path dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().normalize());
    }

    private Path resolveDbPath(String agentKey, Path agentDir) {
        String fileName = StringUtils.hasText(properties.getDbFileName()) ? properties.getDbFileName().trim() : "memory.db";
        if (agentDir != null) {
            return agentDir.toAbsolutePath().normalize().resolve(fileName);
        }
        Path agentsRoot = Path.of(agentProperties.getExternalDir()).toAbsolutePath().normalize();
        return agentsRoot.resolve(requireText(agentKey, "agentKey")).resolve(fileName);
    }

    private Object lockFor(Path dbPath) {
        String key = dbPath.toAbsolutePath().normalize().toString();
        return dbLocks.computeIfAbsent(key, ignored -> new Object());
    }

    private MemoryRecord mapRecord(ResultSet resultSet) throws SQLException {
        return new MemoryRecord(
                resultSet.getString("ID_"),
                resultSet.getString("AGENT_KEY_"),
                resultSet.getString("CONTENT_"),
                resultSet.getString("CATEGORY_"),
                resultSet.getInt("IMPORTANCE_"),
                splitTags(resultSet.getString("TAGS_")),
                resultSet.getBytes("EMBEDDING_") != null,
                resultSet.getLong("CREATED_AT_"),
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

    private Map<String, CandidateScore> readScoredRecords(ResultSet resultSet, String matchType) throws SQLException {
        Map<String, CandidateScore> results = new LinkedHashMap<>();
        while (resultSet.next()) {
            MemoryRecord record = mapRecord(resultSet);
            results.put(record.id(), new CandidateScore(record, resultSet.getDouble("SCORE_"), matchType));
        }
        return results;
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private void touch(Connection connection, Collection<String> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE MEMORIES
                SET ACCESS_COUNT_ = ACCESS_COUNT_ + 1,
                    LAST_ACCESSED_AT_ = ?
                WHERE ID_ = ?
                """)) {
            for (String id : ids) {
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                statement.setLong(1, now);
                statement.setString(2, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private MemoryRecord withTouched(MemoryRecord record) {
        if (record == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return new MemoryRecord(
                record.id(),
                record.agentKey(),
                record.content(),
                record.category(),
                record.importance(),
                record.tags(),
                record.hasEmbedding(),
                record.createdAt(),
                record.updatedAt(),
                record.accessCount() + 1,
                now
        );
    }

    private Optional<float[]> safeEmbed(String text) {
        if (embeddingService == null || !StringUtils.hasText(text)) {
            return Optional.empty();
        }
        try {
            return embeddingService.embed(text)
                    .filter(vector -> vector.length == properties.getEmbeddingDimension());
        } catch (Exception ex) {
            log.debug("Embedding lookup failed, fallback to FTS-only memory path", ex);
            return Optional.empty();
        }
    }

    private String buildFtsQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        for (String token : query.trim().split("\\s+")) {
            String normalized = token == null ? "" : token.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            tokens.add("\"" + normalized.replace("\"", "\"\"") + "\"");
        }
        return String.join(" AND ", tokens);
    }

    private Map<String, Double> normalizeScores(Map<String, CandidateScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }
        double min = scores.values().stream().mapToDouble(CandidateScore::score).min().orElse(0d);
        double max = scores.values().stream().mapToDouble(CandidateScore::score).max().orElse(0d);
        if (Double.compare(max, min) == 0) {
            return scores.values().stream()
                    .collect(LinkedHashMap::new, (map, candidate) -> map.put(candidate.record().id(), 1d), Map::putAll);
        }
        return scores.values().stream()
                .collect(LinkedHashMap::new, (map, candidate) -> map.put(
                        candidate.record().id(),
                        (candidate.score() - min) / (max - min)
                ), Map::putAll);
    }

    private double cosineSimilarity(float[] left, float[] right) {
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private byte[] serializeEmbedding(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] deserializeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] embedding = new float[bytes.length / Float.BYTES];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = buffer.getFloat();
        }
        return embedding;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing argument: " + fieldName);
        }
        return value.trim();
    }

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.trim() : "general";
    }

    private String normalizeOptionalCategory(String category) {
        return StringUtils.hasText(category) ? category.trim() : null;
    }

    private int normalizeImportance(int importance) {
        if (importance < 1 || importance > 10) {
            throw new IllegalArgumentException("Invalid argument: importance must be between 1 and 10");
        }
        return importance;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return properties.getSearchDefaultLimit();
        }
        return limit;
    }

    private String normalizeSort(String sortBy) {
        if (!StringUtils.hasText(sortBy)) {
            return RECENT_SORT;
        }
        String normalized = sortBy.trim().toLowerCase(Locale.ROOT);
        return IMPORTANCE_SORT.equals(normalized) ? IMPORTANCE_SORT : RECENT_SORT;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            normalized.add(tag.trim());
        }
        return List.copyOf(normalized);
    }

    private String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }

    private List<String> splitTags(String serialized) {
        if (!StringUtils.hasText(serialized)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String token : serialized.split(",")) {
            if (StringUtils.hasText(token)) {
                tags.add(token.trim());
            }
        }
        return List.copyOf(tags);
    }

    private record CandidateScore(MemoryRecord record, double score, String matchType) {
        private CandidateScore {
            Objects.requireNonNull(record, "record");
            matchType = matchType == null ? MATCH_FTS : matchType;
        }
    }
}
