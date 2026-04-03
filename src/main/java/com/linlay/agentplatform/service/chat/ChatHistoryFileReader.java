package com.linlay.agentplatform.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.chatstorage.ChatStorageTypes;
import com.linlay.agentplatform.model.api.QueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class ChatHistoryFileReader {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryFileReader.class);

    private final ObjectMapper objectMapper;
    private final Supplier<Charset> charsetSupplier;

    ChatHistoryFileReader(ObjectMapper objectMapper, Supplier<Charset> charsetSupplier) {
        this.objectMapper = objectMapper;
        this.charsetSupplier = charsetSupplier;
    }

    ChatHistoryReadResult read(Path historyPath) {
        if (historyPath == null || !Files.exists(historyPath)) {
            return new ChatHistoryReadResult(List.of(), new LinkedHashMap<>());
        }
        try {
            List<String> lines = Files.readAllLines(historyPath, charsetSupplier.get());
            LinkedHashMap<String, Map<String, Object>> queryByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, Boolean> queryHiddenByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, List<StepEntry>> stepsByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, List<PersistedChatEvent>> eventsByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, List<ArtifactStateEntry>> artifactEntriesByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> firstLineIndexByRunId = new LinkedHashMap<>();
            LinkedHashMap<String, QueryRequest.Reference> references = new LinkedHashMap<>();
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
                mergeFirstLineIndex(firstLineIndexByRunId, runId, lineIndex);

                if ("query".equals(type)) {
                    Map<String, Object> query = new LinkedHashMap<>();
                    if (node.has("query") && node.get("query").isObject()) {
                        query = objectMapper.convertValue(
                                node.get("query"),
                                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)
                        );
                    }
                    queryByRunId.put(runId, query);
                    queryHiddenByRunId.put(runId, node.path("hidden").asBoolean(false));
                    collectReferencesFromQuery(query, references);
                    stepsByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    eventsByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    artifactEntriesByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                } else if ("step".equals(type)) {
                    String chatId = node.path("chatId").asText(null);
                    long updatedAt = node.path("updatedAt").asLong(0);
                    String stage = node.path("_stage").asText(null);
                    int seq = node.path("_seq").asInt(0);
                    String taskId = node.has("taskId") && !node.get("taskId").isNull()
                            ? node.path("taskId").asText(null)
                            : null;

                    ChatStorageTypes.SystemSnapshot system = null;
                    if (node.has("system") && !node.get("system").isNull()) {
                        system = objectMapper.treeToValue(node.get("system"), ChatStorageTypes.SystemSnapshot.class);
                    }

                    ChatStorageTypes.PlanState plan = null;
                    if (node.has("plan") && !node.get("plan").isNull()) {
                        plan = objectMapper.treeToValue(node.get("plan"), ChatStorageTypes.PlanState.class);
                    }

                    ChatStorageTypes.ArtifactState artifacts = null;
                    if (node.has("artifacts") && !node.get("artifacts").isNull()) {
                        artifacts = objectMapper.treeToValue(node.get("artifacts"), ChatStorageTypes.ArtifactState.class);
                    }

                    List<ChatStorageTypes.StoredMessage> messages = new ArrayList<>();
                    if (node.has("messages") && node.get("messages").isArray()) {
                        for (JsonNode msgNode : node.get("messages")) {
                            ChatStorageTypes.StoredMessage message = objectMapper.treeToValue(
                                    msgNode,
                                    ChatStorageTypes.StoredMessage.class
                            );
                            if (message != null) {
                                messages.add(message);
                            }
                        }
                    }

                    stepsByRunId.computeIfAbsent(runId, key -> new ArrayList<>())
                            .add(new StepEntry(chatId, stage, seq, taskId, updatedAt, system, plan, artifacts, messages, lineIndex));
                    eventsByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    artifactEntriesByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    if (hasArtifactItems(artifacts)) {
                        artifactEntriesByRunId.get(runId).add(ArtifactStateEntry.snapshot(updatedAt, lineIndex, artifacts));
                    }
                } else if ("event".equals(type)) {
                    JsonNode eventNode = node.has("event") && node.get("event").isObject()
                            ? node.get("event")
                            : node;
                    String eventType = textValue(eventNode.get("type"));
                    if (!isPersistedEventType(eventType) && !"artifact.publish".equals(eventType)) {
                        lineIndex++;
                        continue;
                    }
                    long eventTimestamp = eventNode.path("timestamp").asLong(node.path("updatedAt").asLong(0));
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
                    eventPayload = normalizePersistedEventPayload(eventType, eventPayload, textValue(node.get("chatId")), runId);
                    stepsByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    eventsByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    artifactEntriesByRunId.computeIfAbsent(runId, key -> new ArrayList<>());
                    if ("artifact.publish".equals(eventType)) {
                        ArtifactStateEntry artifactEntry = artifactEntryFromPublishPayload(eventTimestamp, lineIndex, eventPayload);
                        if (artifactEntry != null) {
                            artifactEntriesByRunId.get(runId).add(artifactEntry);
                        }
                    } else {
                        eventsByRunId.get(runId).add(new PersistedChatEvent(eventType, eventTimestamp, eventPayload, lineIndex));
                    }
                }
                lineIndex++;
            }

            List<ChatHistoryRunSnapshot> runs = new ArrayList<>();
            for (Map.Entry<String, List<StepEntry>> entry : stepsByRunId.entrySet()) {
                String runId = entry.getKey();
                List<StepEntry> steps = entry.getValue();
                List<PersistedChatEvent> explicitPersistedEvents = eventsByRunId.getOrDefault(runId, List.of());
                List<ArtifactStateEntry> artifactEntries = new ArrayList<>(artifactEntriesByRunId.getOrDefault(runId, List.of()));
                if (steps.isEmpty() && explicitPersistedEvents.isEmpty() && artifactEntries.isEmpty()) {
                    continue;
                }
                if (!steps.isEmpty()) {
                    steps.sort(Comparator.comparingInt(StepEntry::seq));
                }
                if (!artifactEntries.isEmpty()) {
                    artifactEntries.sort(Comparator.comparingLong(ArtifactStateEntry::timestamp)
                            .thenComparingInt(ArtifactStateEntry::lineIndex));
                }

                Map<String, Object> query = queryByRunId.getOrDefault(runId, Map.of());
                List<PersistedChatEvent> persistedEvents = List.copyOf(explicitPersistedEvents);
                long updatedAt = Math.max(
                        steps.stream().mapToLong(StepEntry::updatedAt).max().orElse(0),
                        Math.max(
                                persistedEvents.stream().mapToLong(PersistedChatEvent::timestamp).max().orElse(0),
                                artifactEntries.stream().mapToLong(ArtifactStateEntry::timestamp).max().orElse(0)
                        )
                );

                List<ChatStorageTypes.StoredMessage> allMessages = new ArrayList<>();
                ChatStorageTypes.SystemSnapshot firstSystem = null;
                ChatStorageTypes.PlanState latestPlan = null;
                for (StepEntry step : steps) {
                    if (firstSystem == null && step.system() != null) {
                        firstSystem = step.system();
                    }
                    if (step.plan() != null) {
                        latestPlan = step.plan();
                    }
                    allMessages.addAll(step.messages());
                }
                ChatStorageTypes.ArtifactState latestArtifacts = foldArtifactState(artifactEntries);

                int firstLineIndex = firstLineIndexByRunId.getOrDefault(runId, lineIndex);
                runs.add(new ChatHistoryRunSnapshot(
                        runId,
                        updatedAt,
                        queryHiddenByRunId.getOrDefault(runId, false),
                        query,
                        firstSystem,
                        latestPlan,
                        latestArtifacts,
                        List.copyOf(allMessages),
                        persistedEvents,
                        firstLineIndex
                ));
            }
            return new ChatHistoryReadResult(List.copyOf(runs), references);
        } catch (Exception ex) {
            log.warn("Cannot read chat history file={}, fallback to empty", historyPath, ex);
            return new ChatHistoryReadResult(List.of(), new LinkedHashMap<>());
        }
    }

    private void collectReferencesFromQuery(
            Map<String, Object> query,
            LinkedHashMap<String, QueryRequest.Reference> references
    ) {
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
                references.putIfAbsent(reference.id().trim(), reference);
            } catch (Exception ignored) {
                // ignore invalid reference item
            }
        }
    }

    private JsonNode parseLine(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isPersistedEventType(String type) {
        return "request.submit".equals(type)
                || "request.steer".equals(type)
                || "run.cancel".equals(type)
                || "run.error".equals(type)
                || "run.complete".equals(type);
    }

    private Map<String, Object> normalizePersistedEventPayload(
            String eventType,
            Map<String, Object> payload,
            String fallbackChatId,
            String fallbackRunId
    ) {
        if (!"artifact.publish".equals(eventType) || payload == null) {
            return payload;
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(payload);
        String artifactId = textValue(normalized.get("artifactId"));
        Object artifactObject = normalized.get("artifact");
        if (!StringUtils.hasText(artifactId) && artifactObject instanceof Map<?, ?> artifactMap) {
            artifactId = textValue(artifactMap.get("id"));
        }
        if (StringUtils.hasText(artifactId)) {
            normalized.put("artifactId", artifactId.trim());
        } else {
            normalized.remove("artifactId");
        }
        String chatId = textValue(normalized.get("chatId"));
        if (!StringUtils.hasText(chatId)) {
            chatId = fallbackChatId;
        }
        if (StringUtils.hasText(chatId)) {
            normalized.put("chatId", chatId.trim());
        }
        String runId = textValue(normalized.get("runId"));
        if (!StringUtils.hasText(runId)) {
            runId = fallbackRunId;
        }
        if (StringUtils.hasText(runId)) {
            normalized.put("runId", runId.trim());
        }
        normalized.put("artifact", compactArtifactPayload(artifactObject));
        normalized.remove("source");
        return normalized;
    }

    private Map<String, Object> compactArtifactPayload(Object artifactObject) {
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
        if (artifactObject instanceof ChatStorageTypes.ArtifactItemState item) {
            putIfText(artifact, "type", item.type);
            putIfText(artifact, "name", item.name);
            putIfText(artifact, "mimeType", item.mimeType);
            if (item.sizeBytes != null) {
                artifact.put("sizeBytes", item.sizeBytes);
            }
            putIfText(artifact, "url", item.url);
            putIfText(artifact, "sha256", item.sha256);
            return artifact;
        }
        if (!(artifactObject instanceof Map<?, ?> artifactMap)) {
            return artifact;
        }
        putIfText(artifact, "type", artifactMap.get("type"));
        putIfText(artifact, "name", artifactMap.get("name"));
        putIfText(artifact, "mimeType", artifactMap.get("mimeType"));
        Object sizeBytes = artifactMap.get("sizeBytes");
        if (sizeBytes != null) {
            artifact.put("sizeBytes", sizeBytes);
        }
        putIfText(artifact, "url", artifactMap.get("url"));
        putIfText(artifact, "sha256", artifactMap.get("sha256"));
        return artifact;
    }

    private void putIfText(Map<String, Object> target, String key, Object value) {
        String text = textValue(value);
        if (StringUtils.hasText(text)) {
            target.put(key, text.trim());
        }
    }

    private void mergeFirstLineIndex(Map<String, Integer> firstLineIndexByRunId, String runId, int lineIndex) {
        firstLineIndexByRunId.merge(runId, lineIndex, Math::min);
    }

    private boolean hasArtifactItems(ChatStorageTypes.ArtifactState artifacts) {
        return artifacts != null && artifacts.items != null && !artifacts.items.isEmpty();
    }

    private ArtifactStateEntry artifactEntryFromPublishPayload(long timestamp, int lineIndex, Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        ChatStorageTypes.ArtifactItemState item = toArtifactItemState(payload);
        if (item == null) {
            return null;
        }
        return ArtifactStateEntry.delta(timestamp, lineIndex, item);
    }

    private ChatStorageTypes.ArtifactItemState toArtifactItemState(Map<String, Object> payload) {
        String artifactId = textValue(payload.get("artifactId"));
        if (!StringUtils.hasText(artifactId)) {
            return null;
        }
        Object artifactObject = payload.get("artifact");
        if (!(artifactObject instanceof Map<?, ?> artifactMap)) {
            return null;
        }

        ChatStorageTypes.ArtifactItemState item = new ChatStorageTypes.ArtifactItemState();
        item.artifactId = artifactId.trim();
        item.type = textValue(artifactMap.get("type"));
        item.name = textValue(artifactMap.get("name"));
        item.mimeType = textValue(artifactMap.get("mimeType"));
        Object sizeBytes = artifactMap.get("sizeBytes");
        if (sizeBytes instanceof Number number) {
            item.sizeBytes = number.longValue();
        }
        item.url = textValue(artifactMap.get("url"));
        item.sha256 = textValue(artifactMap.get("sha256"));
        if (!StringUtils.hasText(item.type) || !StringUtils.hasText(item.name) || !StringUtils.hasText(item.url)) {
            return null;
        }
        return item;
    }

    private ChatStorageTypes.ArtifactState foldArtifactState(List<ArtifactStateEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, ChatStorageTypes.ArtifactItemState> itemsById = new LinkedHashMap<>();
        for (ArtifactStateEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.snapshot() != null && hasArtifactItems(entry.snapshot())) {
                itemsById.clear();
                for (ChatStorageTypes.ArtifactItemState item : entry.snapshot().items) {
                    rememberArtifactItem(itemsById, item);
                }
                continue;
            }
            rememberArtifactItem(itemsById, entry.item());
        }
        if (itemsById.isEmpty()) {
            return null;
        }
        ChatStorageTypes.ArtifactState state = new ChatStorageTypes.ArtifactState();
        state.items = List.copyOf(itemsById.values());
        return state;
    }

    private void rememberArtifactItem(
            Map<String, ChatStorageTypes.ArtifactItemState> itemsById,
            ChatStorageTypes.ArtifactItemState source
    ) {
        if (itemsById == null || source == null || !StringUtils.hasText(source.artifactId)) {
            return;
        }
        ChatStorageTypes.ArtifactItemState item = new ChatStorageTypes.ArtifactItemState();
        item.artifactId = source.artifactId.trim();
        item.type = textValue(source.type);
        item.name = textValue(source.name);
        item.mimeType = textValue(source.mimeType);
        item.sizeBytes = source.sizeBytes;
        item.url = textValue(source.url);
        item.sha256 = textValue(source.sha256);
        if (!StringUtils.hasText(item.type) || !StringUtils.hasText(item.name) || !StringUtils.hasText(item.url)) {
            return;
        }
        itemsById.put(item.artifactId, item);
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String textValue(Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private record StepEntry(
            String chatId,
            String stage,
            int seq,
            String taskId,
            long updatedAt,
            ChatStorageTypes.SystemSnapshot system,
            ChatStorageTypes.PlanState plan,
            ChatStorageTypes.ArtifactState artifacts,
            List<ChatStorageTypes.StoredMessage> messages,
            int lineIndex
    ) {
    }

    private record ArtifactStateEntry(
            long timestamp,
            int lineIndex,
            ChatStorageTypes.ArtifactState snapshot,
            ChatStorageTypes.ArtifactItemState item
    ) {
        private static ArtifactStateEntry snapshot(long timestamp, int lineIndex, ChatStorageTypes.ArtifactState state) {
            return new ArtifactStateEntry(timestamp, lineIndex, state, null);
        }

        private static ArtifactStateEntry delta(long timestamp, int lineIndex, ChatStorageTypes.ArtifactItemState item) {
            return new ArtifactStateEntry(timestamp, lineIndex, null, item);
        }
    }
}
