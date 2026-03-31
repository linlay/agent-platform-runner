package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.MemoryStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentMemoryService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final MemoryStorageProperties memoryStorageProperties;
    private final ObjectMapper objectMapper;

    public AgentMemoryService() {
        this(new MemoryStorageProperties(), new ObjectMapper());
    }

    @Autowired
    public AgentMemoryService(MemoryStorageProperties memoryStorageProperties, ObjectMapper objectMapper) {
        this.memoryStorageProperties = memoryStorageProperties == null ? new MemoryStorageProperties() : memoryStorageProperties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Path resolveMemoryRoot() {
        return Path.of(memoryStorageProperties.getDir()).toAbsolutePath().normalize();
    }

    public Path resolveMemoryDbPath() {
        return resolveMemoryRoot().resolve("memory.db");
    }

    public Path resolveJournalPath(LocalDate date) {
        LocalDate normalizedDate = date == null ? LocalDate.now() : date;
        return resolveMemoryRoot()
                .resolve("journal")
                .resolve(MONTH_FORMATTER.format(normalizedDate))
                .resolve(DATE_FORMATTER.format(normalizedDate) + ".jsonl");
    }

    public String relativeJournalPath(LocalDate date) {
        LocalDate normalizedDate = date == null ? LocalDate.now() : date;
        return "journal/" + MONTH_FORMATTER.format(normalizedDate) + "/" + DATE_FORMATTER.format(normalizedDate) + ".jsonl";
    }

    public void appendJournalEntry(
            String id,
            Instant timestamp,
            String requestId,
            String chatId,
            String agentKey,
            String subjectKey,
            String summary,
            String sourceType,
            String category,
            int importance,
            java.util.List<String> tags
    ) {
        if (!StringUtils.hasText(id) || !StringUtils.hasText(summary)) {
            return;
        }
        Instant normalizedTs = timestamp == null ? Instant.now() : timestamp;
        LocalDate date = normalizedTs.atZone(ZoneId.systemDefault()).toLocalDate();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", id);
        line.put("ts", normalizedTs.toEpochMilli());
        line.put("date", DATE_FORMATTER.format(date));
        line.put("requestId", trimToNull(requestId));
        line.put("chatId", trimToNull(chatId));
        line.put("agentKey", trimToNull(agentKey));
        line.put("subjectKey", trimToNull(subjectKey));
        line.put("summary", summary.trim());
        line.put("sourceType", trimToNull(sourceType));
        if (StringUtils.hasText(category)) {
            line.put("category", category.trim());
        }
        line.put("importance", importance);
        if (tags != null && !tags.isEmpty()) {
            line.put("tags", tags);
        }
        appendJsonLine(resolveJournalPath(date), line);
    }

    private void appendJsonLine(Path path, Map<String, Object> payload) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    toJson(payload) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to append memory journal entry: " + path, ex);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize memory journal entry", ex);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
