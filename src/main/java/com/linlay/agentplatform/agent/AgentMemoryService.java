package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.properties.MemoryStorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ENTRY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemoryStorageProperties memoryStorageProperties;

    public AgentMemoryService() {
        this(new MemoryStorageProperties(), new ObjectMapper());
    }

    @Autowired
    public AgentMemoryService(MemoryStorageProperties memoryStorageProperties, ObjectMapper objectMapper) {
        this.memoryStorageProperties = memoryStorageProperties == null ? new MemoryStorageProperties() : memoryStorageProperties;
    }

    @PostConstruct
    void logMemoryRoot() {
        log.info("Central memory root: {}", resolveMemoryRoot());
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
                .resolve(DATE_FORMATTER.format(normalizedDate) + ".md");
    }

    public String relativeJournalPath(LocalDate date) {
        LocalDate normalizedDate = date == null ? LocalDate.now() : date;
        return "journal/" + MONTH_FORMATTER.format(normalizedDate) + "/" + DATE_FORMATTER.format(normalizedDate) + ".md";
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
        if (!StringUtils.hasText(id) || !StringUtils.hasText(summary) || !StringUtils.hasText(chatId)) {
            return;
        }
        Instant normalizedTs = timestamp == null ? Instant.now() : timestamp;
        ZonedDateTime zonedDateTime = normalizedTs.atZone(ZoneId.systemDefault());
        LocalDate date = zonedDateTime.toLocalDate();
        appendMarkdownEntry(resolveJournalPath(date), formatMarkdownEntry(
                zonedDateTime,
                chatId,
                agentKey,
                subjectKey,
                summary
        ));
    }

    private void appendMarkdownEntry(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to append memory journal entry: " + path, ex);
        }
    }

    private String formatMarkdownEntry(
            ZonedDateTime zonedDateTime,
            String chatId,
            String agentKey,
            String subjectKey,
            String summary
    ) {
        String normalizedSummary = normalizeSummary(summary);
        StringBuilder builder = new StringBuilder()
                .append("## ")
                .append(ENTRY_TIMESTAMP_FORMATTER.format(zonedDateTime))
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        String normalizedSubjectKey = normalizeOptional(subjectKey);
        if (normalizedSubjectKey != null) {
            builder.append("主题：").append(normalizedSubjectKey).append(System.lineSeparator());
        }
        builder.append("chatId: ").append(chatId.trim()).append(System.lineSeparator());
        builder.append("agentKey: ").append(normalizeAgentKey(agentKey)).append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append(normalizedSummary)
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        return builder.toString();
    }

    private String normalizeSummary(String summary) {
        return summary == null
                ? ""
                : summary.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeAgentKey(String agentKey) {
        return StringUtils.hasText(agentKey) ? agentKey.trim() : "_unknown";
    }
}
