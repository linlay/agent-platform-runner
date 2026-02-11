package com.linlay.springaiagw.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.springaiagw.memory.ChatWindowMemoryProperties;
import com.linlay.springaiagw.memory.ChatWindowMemoryStore;
import com.linlay.springaiagw.model.agw.AgwChatDetailResponse;
import com.linlay.springaiagw.model.agw.AgwChatSummaryResponse;
import com.linlay.springaiagw.model.agw.AgwQueryRequest;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatRecordStore {

    private static final Logger log = LoggerFactory.getLogger(ChatRecordStore.class);
    private static final String CHAT_INDEX_FILE = "_chats.jsonl";
    private static final String RECORD_TYPE = "recordType";

    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryProperties properties;
    private final Object lock = new Object();

    public ChatRecordStore(ObjectMapper objectMapper, ChatWindowMemoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ChatSummary ensureChat(String chatId, String agentKey, String firstMessage) {
        requireValidChatId(chatId);
        synchronized (lock) {
            List<ChatIndexRecord> records = readIndexRecords();
            for (ChatIndexRecord record : records) {
                if (chatId.equals(record.chatId)) {
                    return toChatSummary(record);
                }
            }

            long now = System.currentTimeMillis();
            ChatIndexRecord record = new ChatIndexRecord();
            record.chatId = chatId;
            record.chatName = deriveChatName(firstMessage);
            record.firstAgentKey = nullable(agentKey);
            record.createdAt = now;

            appendJsonLine(resolveIndexPath(), objectMapper.valueToTree(record));
            return toChatSummary(record);
        }
    }

    public void appendRequest(
            String chatId,
            String requestId,
            String runId,
            String agentKey,
            String message,
            List<AgwQueryRequest.Reference> references,
            AgwQueryRequest.Scene scene
    ) {
        // recordType snapshots are disabled; request metadata is no longer persisted to chat files.
    }

    public void appendEvent(String chatId, String eventData) {
        // recordType snapshots are disabled; events are only emitted over SSE/console.
    }

    public List<AgwChatSummaryResponse> listChats() {
        synchronized (lock) {
            return readIndexRecords().stream()
                    .sorted((left, right) -> Long.compare(right.createdAt, left.createdAt))
                    .map(record -> new AgwChatSummaryResponse(
                            record.chatId,
                            record.chatName,
                            record.firstAgentKey,
                            record.createdAt
                    ))
                    .toList();
        }
    }

    public AgwChatDetailResponse loadChat(String chatId, boolean includeEvents) {
        requireValidChatId(chatId);
        Path historyPath = resolveHistoryPath(chatId);
        synchronized (lock) {
            Optional<ChatIndexRecord> indexRecord = readIndexRecords().stream()
                    .filter(item -> chatId.equals(item.chatId))
                    .findFirst();

            if (indexRecord.isEmpty() && !Files.exists(historyPath)) {
                throw new ChatNotFoundException(chatId);
            }

            ChatSummary summary = indexRecord
                    .map(this::toChatSummary)
                    .orElseGet(() -> new ChatSummary(chatId, chatId, null, resolveCreatedAt(historyPath)));

            ParsedChatContent content = readChatContent(historyPath);
            List<Map<String, Object>> events = includeEvents ? List.copyOf(content.events) : null;
            List<AgwQueryRequest.Reference> references = content.references.isEmpty()
                    ? null
                    : List.copyOf(content.references.values());

            return new AgwChatDetailResponse(
                    summary.chatId,
                    summary.chatName,
                    summary.firstAgentKey,
                    summary.createdAt,
                    List.copyOf(content.messages),
                    events,
                    references
            );
        }
    }

    private ParsedChatContent readChatContent(Path historyPath) {
        ParsedChatContent content = new ParsedChatContent();
        readHistoryLines(historyPath, content);
        return content;
    }

    private void readHistoryLines(Path historyPath, ParsedChatContent content) {
        if (!Files.exists(historyPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(historyPath, resolveCharset());
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

                String recordType = textValue(node.get(RECORD_TYPE));
                if (StringUtils.hasText(recordType)) {
                    lineIndex++;
                    continue;
                }

                ChatWindowMemoryStore.RunRecord runRecord = toRunRecord(node, lineIndex);
                if (runRecord == null || runRecord.messages == null || runRecord.messages.isEmpty()) {
                    lineIndex++;
                    continue;
                }
                for (ChatWindowMemoryStore.StoredMessage message : runRecord.messages) {
                    Map<String, Object> normalized = toMessageMap(runRecord.runId, message);
                    if (!normalized.isEmpty()) {
                        content.messages.add(normalized);
                    }
                }
                lineIndex++;
            }
        } catch (Exception ex) {
            log.warn("Cannot read chat history file={}, fallback to empty", historyPath, ex);
        }
    }

    private Map<String, Object> toMessageMap(String runId, ChatWindowMemoryStore.StoredMessage message) {
        if (message == null || !StringUtils.hasText(message.role)) {
            return Map.of();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("role", message.role);
        root.put("content", message.content);
        root.put("ts", message.ts);
        putIfText(root, "runId", runId);
        putIfText(root, "name", message.name);
        putIfText(root, "toolCallId", message.toolCallId);
        putIfText(root, "toolResult", message.toolResult);
        if (message.toolArgs != null && !message.toolArgs.isNull()) {
            root.put("toolArgs", objectMapper.convertValue(message.toolArgs, Object.class));
        }
        return root;
    }

    private ChatWindowMemoryStore.RunRecord toRunRecord(JsonNode node, int lineIndex) {
        JsonNode messages = node.get("messages");
        if (messages == null || !messages.isArray()) {
            return null;
        }
        try {
            ChatWindowMemoryStore.RunRecord run = objectMapper.treeToValue(node, ChatWindowMemoryStore.RunRecord.class);
            if (run == null) {
                return null;
            }
            if (!StringUtils.hasText(run.runId)) {
                run.runId = "legacy-" + lineIndex;
            }
            return run;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ChatIndexRecord> readIndexRecords() {
        Path path = resolveIndexPath();
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<ChatIndexRecord> records = new ArrayList<>();
            List<String> lines = Files.readAllLines(path, resolveCharset());
            for (String line : lines) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                JsonNode node = parseLine(line);
                if (node == null || !node.isObject()) {
                    continue;
                }
                ChatIndexRecord record = objectMapper.treeToValue(node, ChatIndexRecord.class);
                if (record == null || !isValidChatId(record.chatId)) {
                    continue;
                }
                if (!StringUtils.hasText(record.chatName)) {
                    record.chatName = record.chatId;
                }
                records.add(record);
            }
            return List.copyOf(records);
        } catch (Exception ex) {
            log.warn("Cannot read chat index file={}, fallback to empty", path, ex);
            return List.of();
        }
    }

    private void appendJsonLine(Path path, JsonNode node) {
        try {
            Files.createDirectories(path.getParent());
            String line = objectMapper.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(
                    path,
                    line,
                    resolveCharset(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot append chat record file=" + path, ex);
        }
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
        int[] codePoints = normalized.codePoints().limit(10).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private Path resolveBaseDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private Path resolveIndexPath() {
        return resolveBaseDir().resolve(CHAT_INDEX_FILE);
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

    private ChatSummary toChatSummary(ChatIndexRecord record) {
        return new ChatSummary(
                record.chatId,
                StringUtils.hasText(record.chatName) ? record.chatName : record.chatId,
                nullable(record.firstAgentKey),
                record.createdAt
        );
    }

    private String nullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void putIfText(ObjectNode node, String key, String value) {
        if (StringUtils.hasText(value)) {
            node.put(key, value.trim());
        }
    }

    private void putIfText(Map<String, Object> node, String key, String value) {
        if (StringUtils.hasText(value)) {
            node.put(key, value.trim());
        }
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Map<String, Object> asMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    public record ChatSummary(
            String chatId,
            String chatName,
            String firstAgentKey,
            long createdAt
    ) {
    }

    private static final class ParsedChatContent {
        private final List<Map<String, Object>> messages = new ArrayList<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final LinkedHashMap<String, AgwQueryRequest.Reference> references = new LinkedHashMap<>();
    }

    private static final class ChatIndexRecord {
        public String chatId;
        public String chatName;
        public String firstAgentKey;
        public long createdAt;
    }
}
