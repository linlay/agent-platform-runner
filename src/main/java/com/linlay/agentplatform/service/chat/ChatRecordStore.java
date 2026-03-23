package com.linlay.agentplatform.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.memory.ChatMemoryTypes;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话记录读取与快照回放服务。
 * <p>
 * 负责维护聊天索引（会话列表元数据）、读取运行历史文件、并将持久化消息转换为
 * 前端可消费的事件快照与原始消息列表。该类不参与实时 SSE 发送，仅处理存储侧视图重建。
 */
@Service
public class ChatRecordStore {

    private static final Logger log = LoggerFactory.getLogger(ChatRecordStore.class);

    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryProperties properties;
    private final ChatAssetCatalogService chatAssetCatalogService;
    private final Object lock = new Object();
    private final ChatIndexRepository chatIndexRepository;
    private final ChatHistoryFileReader chatHistoryFileReader;
    private final ChatEventSnapshotBuilder chatEventSnapshotBuilder;

    public ChatRecordStore(ObjectMapper objectMapper, ChatWindowMemoryProperties properties) {
        this(objectMapper, properties, null, null);
    }

    @Autowired
    public ChatRecordStore(
            ObjectMapper objectMapper,
            ChatWindowMemoryProperties properties,
            ToolRegistry toolRegistry,
            ChatAssetCatalogService chatAssetCatalogService
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatAssetCatalogService = chatAssetCatalogService;
        this.chatIndexRepository = new ChatIndexRepository(properties, lock);
        this.chatHistoryFileReader = new ChatHistoryFileReader(objectMapper, this::resolveCharset);
        this.chatEventSnapshotBuilder = new ChatEventSnapshotBuilder(objectMapper, toolRegistry);
    }

    public ChatRecordStore(ObjectMapper objectMapper, ChatWindowMemoryProperties properties, ToolRegistry toolRegistry) {
        this(objectMapper, properties, toolRegistry, null);
    }

    @PostConstruct
    public void initializeDatabase() {
        chatIndexRepository.initializeDatabase();
    }

    public ChatSummary ensureChat(String chatId, String firstAgentKey, String firstAgentName, String firstMessage) {
        return ensureChat(chatId, firstAgentKey, firstAgentName, null, firstMessage);
    }

    public ChatSummary ensureChat(String chatId, String firstAgentKey, String firstAgentName, String firstTeamId, String firstMessage) {
        return chatIndexRepository.ensureChat(chatId, firstAgentKey, firstAgentName, firstTeamId, firstMessage);
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
        return chatIndexRepository.findBoundAgentKey(chatId);
    }

    public Optional<String> findBoundTeamId(String chatId) {
        return chatIndexRepository.findBoundTeamId(chatId);
    }

    public void onRunCompleted(RunCompletion completion) {
        chatIndexRepository.onRunCompleted(completion);
    }

    public List<ChatSummaryResponse> listChats() {
        return listChats(null, null);
    }

    public List<ChatSummaryResponse> listChats(String lastRunId) {
        return listChats(lastRunId, null);
    }

    public List<ChatSummaryResponse> listChats(String lastRunId, String agentKey) {
        return chatIndexRepository.listChats(lastRunId, agentKey);
    }

    public MarkChatReadResult markChatRead(String chatId) {
        return chatIndexRepository.markChatRead(chatId);
    }

    public ChatDetailResponse loadChat(String chatId, boolean includeRawMessages) {
        requireValidChatId(chatId);
        Path historyPath = resolveHistoryPath(chatId);
        synchronized (lock) {
            com.linlay.agentplatform.service.chat.ChatIndexRecord indexRecord = chatIndexRepository.loadChatRecord(chatId);

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
                    summary.chatName,
                    summary.agentKey
            );

            List<Map<String, Object>> events = List.copyOf(content.events);
            List<Map<String, Object>> rawMessages = includeRawMessages
                    ? List.copyOf(content.rawMessages)
                    : null;
            List<QueryRequest.Reference> references = mergeChatAssetReferences(
                    summary.chatId,
                    content.references.isEmpty() ? null : List.copyOf(content.references.values())
            );

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
            String chatName,
            String boundAgentKey
    ) {
        ParsedChatContent content = new ParsedChatContent();
        ChatHistoryReadResult history = chatHistoryFileReader.read(historyPath);
        content.runs.addAll(history.runs());
        content.references.putAll(history.references());

        content.runs.sort(
                Comparator.comparingLong(this::sortByUpdatedAt)
                        .thenComparingInt(ChatHistoryRunSnapshot::lineIndex)
        );

        for (ChatHistoryRunSnapshot run : content.runs) {
            for (ChatMemoryTypes.StoredMessage message : run.messages()) {
                Map<String, Object> raw = toRawMessageMap(run.runId(), message);
                if (!raw.isEmpty()) {
                    content.rawMessages.add(raw);
                }
            }
        }
        content.events.addAll(chatEventSnapshotBuilder.buildSnapshotEvents(chatId, chatName, boundAgentKey, content.runs));
        return content;
    }

    private List<QueryRequest.Reference> mergeChatAssetReferences(String chatId, List<QueryRequest.Reference> references) {
        if (chatAssetCatalogService == null || !StringUtils.hasText(chatId)) {
            return references == null || references.isEmpty() ? null : List.copyOf(references);
        }
        try {
            List<QueryRequest.Reference> merged = chatAssetCatalogService.mergeWithChatAssets(chatId, references);
            return merged.isEmpty() ? null : merged;
        } catch (Exception ex) {
            log.warn("Failed to merge chat asset references chatId={}", chatId, ex);
            return references == null || references.isEmpty() ? null : List.copyOf(references);
        }
    }

    private long sortByUpdatedAt(ChatHistoryRunSnapshot run) {
        if (run == null || run.updatedAt() <= 0) {
            return Long.MAX_VALUE;
        }
        return run.updatedAt();
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

    private Map<String, Object> planUpdatePayload(
            ChatMemoryTypes.PlanState planState,
            String chatId
    ) {
        if (planState == null
                || !StringUtils.hasText(planState.planId)
                || planState.tasks == null
                || planState.tasks.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        for (ChatMemoryTypes.PlanTaskState task : planState.tasks) {
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", planState.planId.trim());
        payload.put("chatId", StringUtils.hasText(chatId) ? chatId : null);
        payload.put("plan", plan);
        return payload;
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private Map<String, Object> toRawMessageMap(String runId, ChatMemoryTypes.StoredMessage message) {
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
        return StringHelpers.isValidChatId(chatId);
    }

    private Path resolveBaseDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    private Path resolveHistoryPath(String chatId) {
        Path baseDir = resolveBaseDir();
        return baseDir.resolve(chatId + ".jsonl");
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
        return "request.submit".equals(type)
                || "request.steer".equals(type)
                || "run.cancel".equals(type);
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private ChatSummary toChatSummary(com.linlay.agentplatform.service.chat.ChatIndexRecord record) {
        return toChatSummary(record, false);
    }

    private ChatSummary toChatSummary(com.linlay.agentplatform.service.chat.ChatIndexRecord record, boolean created) {
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
        return StringHelpers.nullable(value);
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
        private final List<ChatHistoryRunSnapshot> runs = new ArrayList<>();
        private final List<Map<String, Object>> rawMessages = new ArrayList<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final LinkedHashMap<String, QueryRequest.Reference> references = new LinkedHashMap<>();
    }
}
