package com.linlay.agentplatform.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.util.StringHelpers;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ChatWindowMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(ChatWindowMemoryStore.class);

    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryProperties properties;
    private final StoredMessageConverter storedMessageConverter;

    public ChatWindowMemoryStore(ObjectMapper objectMapper, ChatWindowMemoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.storedMessageConverter = new StoredMessageConverter(objectMapper, properties);
    }

    public List<ChatMessage> loadHistoryMessages(String chatId) {
        if (!isValidChatId(chatId)) {
            return List.of();
        }

        int windowSize = normalizedWindowSize();
        List<ParsedLine> allLines = readAllParsedLines(chatId);
        LinkedHashMap<String, List<ParsedStepLine>> stepsByRunId = new LinkedHashMap<>();
        for (ParsedLine line : allLines) {
            if (line instanceof ParsedStepLine step) {
                stepsByRunId.computeIfAbsent(step.runId(), key -> new ArrayList<>()).add(step);
            }
        }

        List<String> runIds = new ArrayList<>(stepsByRunId.keySet());
        int fromIndex = Math.max(0, runIds.size() - windowSize);
        List<String> recentRunIds = runIds.subList(fromIndex, runIds.size());

        List<ChatMessage> messages = new ArrayList<>();
        for (String runId : recentRunIds) {
            List<ParsedStepLine> steps = stepsByRunId.get(runId);
            steps.sort(Comparator.comparingInt(ParsedStepLine::seq));
            for (ParsedStepLine step : steps) {
                if (step.messages() == null) {
                    continue;
                }
                for (ChatMemoryTypes.StoredMessage stored : step.messages()) {
                    ChatMessage converted = storedMessageConverter.toChatMessage(stored);
                    if (converted != null) {
                        messages.add(converted);
                    }
                }
            }
        }
        return List.copyOf(messages);
    }

    public void appendQueryLine(String chatId, String runId, Map<String, Object> query) {
        if (!isValidChatId(chatId)) {
            return;
        }

        long now = System.currentTimeMillis();
        ChatMemoryTypes.QueryLine line = new ChatMemoryTypes.QueryLine();
        line.chatId = chatId;
        line.runId = normalizeRunId(runId);
        line.updatedAt = now;
        line.hidden = extractHiddenQueryFlag(query) ? Boolean.TRUE : null;
        line.query = normalizeQueryWithoutHidden(query);
        appendLine(chatId, line);
    }

    public void appendStepLine(
            String chatId,
            String runId,
            String stage,
            int seq,
            String taskId,
            ChatMemoryTypes.SystemSnapshot system,
            ChatMemoryTypes.PlanState plan,
            List<ChatMemoryTypes.RunMessage> runMessages
    ) {
        if (!isValidChatId(chatId) || runMessages == null || runMessages.isEmpty()) {
            return;
        }

        String normalizedRunId = normalizeRunId(runId);
        ChatMemoryTypes.SystemSnapshot normalizedSystem = storedMessageConverter.normalizeSystemSnapshot(system);
        Set<String> runtimeActionTools = storedMessageConverter.extractActionToolNames(normalizedSystem);
        TextBlockSequenceState sequenceState = nextTextBlockSequenceState(chatId, normalizedRunId);
        List<ChatMemoryTypes.StoredMessage> storedMessages = storedMessageConverter.convertRunMessages(
                normalizedRunId,
                runMessages,
                runtimeActionTools,
                sequenceState
        );
        if (storedMessages.isEmpty()) {
            return;
        }

        ChatMemoryTypes.StepLine line = new ChatMemoryTypes.StepLine();
        line.chatId = chatId;
        line.runId = normalizedRunId;
        line.stage = hasText(stage) ? stage.trim() : "oneshot";
        line.seq = seq;
        line.taskId = hasText(taskId) ? taskId.trim() : null;
        line.updatedAt = System.currentTimeMillis();
        line.system = normalizedSystem;
        line.plan = storedMessageConverter.normalizePlanState(plan);
        line.messages = storedMessages;
        appendLine(chatId, line);
    }

    public ChatMemoryTypes.PlanState loadLatestPlanState(String chatId) {
        if (!isValidChatId(chatId)) {
            return null;
        }
        List<ParsedLine> lines = readAllParsedLines(chatId);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i) instanceof ParsedStepLine step && step.plan() != null) {
                ChatMemoryTypes.PlanState normalized = storedMessageConverter.normalizePlanState(step.plan());
                if (normalized != null && normalized.tasks != null && !normalized.tasks.isEmpty()) {
                    return normalized;
                }
            }
        }
        return null;
    }

    public ChatMemoryTypes.SystemSnapshot loadLatestSystemSnapshot(String chatId) {
        if (!isValidChatId(chatId)) {
            return null;
        }
        List<ParsedLine> lines = readAllParsedLines(chatId);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i) instanceof ParsedStepLine step && step.system() != null) {
                return step.system();
            }
        }
        return null;
    }

    public void trimToWindow(String chatId) {
        int windowSize = normalizedWindowSize();
        Path path = resolvePath(chatId);
        if (!Files.exists(path)) {
            return;
        }

        try {
            List<String> rawLines = Files.readAllLines(path, resolveCharset()).stream()
                    .filter(StringUtils::hasText)
                    .toList();
            if (rawLines.isEmpty()) {
                return;
            }

            LinkedHashMap<String, List<Integer>> lineIndexesByRunId = new LinkedHashMap<>();
            for (int i = 0; i < rawLines.size(); i++) {
                String runId = extractRunId(rawLines.get(i));
                if (runId != null) {
                    lineIndexesByRunId.computeIfAbsent(runId, key -> new ArrayList<>()).add(i);
                }
            }

            if (lineIndexesByRunId.size() <= windowSize) {
                return;
            }

            List<String> runIds = new ArrayList<>(lineIndexesByRunId.keySet());
            int keepFrom = runIds.size() - windowSize;
            java.util.Set<Integer> dropIndexes = new java.util.HashSet<>();
            for (int i = 0; i < keepFrom; i++) {
                dropIndexes.addAll(lineIndexesByRunId.get(runIds.get(i)));
            }

            List<String> retained = new ArrayList<>(rawLines.size() - dropIndexes.size());
            for (int i = 0; i < rawLines.size(); i++) {
                if (!dropIndexes.contains(i)) {
                    retained.add(rawLines.get(i));
                }
            }

            String content = String.join(System.lineSeparator(), retained) + System.lineSeparator();
            Files.writeString(
                    path,
                    content,
                    resolveCharset(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot trim memory for chatId=" + chatId, ex);
        }
    }

    public boolean isSameSystem(ChatMemoryTypes.SystemSnapshot left, ChatMemoryTypes.SystemSnapshot right) {
        if (left == null || right == null) {
            return false;
        }
        JsonNode leftNode = objectMapper.valueToTree(left);
        JsonNode rightNode = objectMapper.valueToTree(right);
        return leftNode.equals(rightNode);
    }

    private List<ParsedLine> readAllParsedLines(String chatId) {
        Path path = resolvePath(chatId);
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            List<ParsedLine> lines = new ArrayList<>();
            for (String rawLine : Files.readAllLines(path, resolveCharset())) {
                if (!StringUtils.hasText(rawLine)) {
                    continue;
                }
                ParsedLine parsed = parseParsedLine(rawLine);
                if (parsed != null) {
                    lines.add(parsed);
                }
            }
            return List.copyOf(lines);
        } catch (Exception ex) {
            log.warn("Cannot read memory file for chatId={}, fallback to empty history", chatId, ex);
            return List.of();
        }
    }

    private ParsedLine parseParsedLine(String rawLine) {
        try {
            JsonNode node = objectMapper.readTree(rawLine);
            if (node == null || !node.isObject()) {
                return null;
            }
            return switch (node.path("_type").asText("")) {
                case "query" -> parseQueryLineNode(node);
                case "step" -> parseStepLineNode(node);
                default -> null;
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private ParsedQueryLine parseQueryLineNode(JsonNode node) {
        String chatId = node.path("chatId").asText(null);
        String runId = node.path("runId").asText(null);
        long updatedAt = node.path("updatedAt").asLong(0);
        boolean hidden = node.path("hidden").asBoolean(false);
        Map<String, Object> query;
        if (node.has("query") && node.get("query").isObject()) {
            query = objectMapper.convertValue(
                    node.get("query"),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
            );
        } else {
            query = new LinkedHashMap<>();
        }
        return new ParsedQueryLine(chatId, runId, updatedAt, hidden, query);
    }

    private ParsedStepLine parseStepLineNode(JsonNode node) {
        try {
            String chatId = node.path("chatId").asText(null);
            String runId = node.path("runId").asText(null);
            String stage = node.path("_stage").asText(null);
            int seq = node.path("_seq").asInt(0);
            String taskId = node.has("taskId") && !node.get("taskId").isNull()
                    ? node.path("taskId").asText(null)
                    : null;
            long updatedAt = node.path("updatedAt").asLong(0);

            ChatMemoryTypes.SystemSnapshot system = null;
            if (node.has("system") && !node.get("system").isNull()) {
                system = objectMapper.treeToValue(node.get("system"), ChatMemoryTypes.SystemSnapshot.class);
            }

            JsonNode planNode = null;
            if (node.has("plan") && !node.get("plan").isNull()) {
                planNode = node.get("plan");
            }
            ChatMemoryTypes.PlanState plan = planNode == null
                    ? null
                    : objectMapper.treeToValue(planNode, ChatMemoryTypes.PlanState.class);

            List<ChatMemoryTypes.StoredMessage> messages = new ArrayList<>();
            if (node.has("messages") && node.get("messages").isArray()) {
                for (JsonNode msgNode : node.get("messages")) {
                    ChatMemoryTypes.StoredMessage msg = objectMapper.treeToValue(msgNode, ChatMemoryTypes.StoredMessage.class);
                    if (msg != null) {
                        messages.add(msg);
                    }
                }
            }

            return new ParsedStepLine(chatId, runId, stage, seq, taskId, updatedAt, system, plan, List.copyOf(messages));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractRunId(String rawLine) {
        try {
            JsonNode node = objectMapper.readTree(rawLine);
            if (node != null && node.has("runId")) {
                String runId = node.path("runId").asText(null);
                return hasText(runId) ? runId : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void appendLine(String chatId, Object line) {
        Path path = resolvePath(chatId);
        try {
            Files.createDirectories(path.getParent());
            String jsonLine = objectMapper.writeValueAsString(line) + System.lineSeparator();
            Files.writeString(
                    path,
                    jsonLine,
                    resolveCharset(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot append memory line for chatId=" + chatId, ex);
        }
    }

    private boolean extractHiddenQueryFlag(Map<String, Object> query) {
        if (query == null) {
            return false;
        }
        Object hidden = query.get("hidden");
        if (hidden instanceof Boolean value) {
            return value;
        }
        if (hidden instanceof Number value) {
            return value.intValue() != 0;
        }
        if (hidden instanceof String value && !value.isBlank()) {
            return Boolean.parseBoolean(value.trim());
        }
        return false;
    }

    private Map<String, Object> normalizeQueryWithoutHidden(Map<String, Object> query) {
        if (query == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> normalized = objectMapper.convertValue(
                query,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
        );
        normalized.remove("hidden");
        return normalized;
    }

    private String normalizeRunId(String runId) {
        if (hasText(runId)) {
            return runId.trim();
        }
        return Long.toString(System.currentTimeMillis(), 36);
    }

    private TextBlockSequenceState nextTextBlockSequenceState(String chatId, String runId) {
        int maxReasoningSeq = 0;
        int maxContentSeq = 0;
        for (ParsedLine line : readAllParsedLines(chatId)) {
            if (!(line instanceof ParsedStepLine step) || !Objects.equals(runId, step.runId()) || step.messages() == null) {
                continue;
            }
            for (ChatMemoryTypes.StoredMessage message : step.messages()) {
                if (message == null) {
                    continue;
                }
                maxReasoningSeq = Math.max(maxReasoningSeq, extractTextBlockSequence(runId, message.reasoningId, "_r_"));
                maxContentSeq = Math.max(maxContentSeq, extractTextBlockSequence(runId, message.contentId, "_c_"));
            }
        }
        return new TextBlockSequenceState(maxReasoningSeq + 1, maxContentSeq + 1);
    }

    private int extractTextBlockSequence(String runId, String id, String separator) {
        if (!hasText(runId) || !hasText(id) || !id.startsWith(runId + separator)) {
            return 0;
        }
        String suffix = id.substring((runId + separator).length());
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int normalizedWindowSize() {
        return Math.max(1, properties.getK());
    }

    private Path resolvePath(String chatId) {
        Path dir = Paths.get(properties.getDir()).toAbsolutePath().normalize();
        return dir.resolve(chatId + ".jsonl");
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

    private boolean hasText(String value) {
        return StringHelpers.hasText(value);
    }

    private boolean isValidChatId(String chatId) {
        return StringHelpers.isValidChatId(chatId);
    }
}
