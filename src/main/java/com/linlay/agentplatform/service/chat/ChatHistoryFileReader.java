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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                } else if ("event".equals(type)) {
                    JsonNode eventNode = node.has("event") && node.get("event").isObject()
                            ? node.get("event")
                            : node;
                    String eventType = textValue(eventNode.get("type"));
                    if (!isPersistedEventType(eventType)) {
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
                    eventsByRunId.computeIfAbsent(runId, key -> new ArrayList<>())
                            .add(new PersistedChatEvent(eventType, eventTimestamp, eventPayload, lineIndex));
                }
                lineIndex++;
            }

            List<ChatHistoryRunSnapshot> runs = new ArrayList<>();
            for (Map.Entry<String, List<StepEntry>> entry : stepsByRunId.entrySet()) {
                String runId = entry.getKey();
                List<StepEntry> steps = entry.getValue();
                List<PersistedChatEvent> explicitPersistedEvents = eventsByRunId.getOrDefault(runId, List.of());
                if (steps.isEmpty() && explicitPersistedEvents.isEmpty()) {
                    continue;
                }
                if (!steps.isEmpty()) {
                    steps.sort(Comparator.comparingInt(StepEntry::seq));
                }

                Map<String, Object> query = queryByRunId.getOrDefault(runId, Map.of());
                String resolvedChatId = resolveRunChatId(query, steps);
                List<PersistedChatEvent> persistedEvents = new ArrayList<>(explicitPersistedEvents);
                persistedEvents.addAll(syntheticArtifactEvents(runId, resolvedChatId, steps, explicitPersistedEvents));
                long updatedAt = Math.max(
                        steps.stream().mapToLong(StepEntry::updatedAt).max().orElse(0),
                        persistedEvents.stream().mapToLong(PersistedChatEvent::timestamp).max().orElse(0)
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

                int firstLineIndex = !steps.isEmpty()
                        ? steps.getFirst().lineIndex()
                        : persistedEvents.stream().mapToInt(PersistedChatEvent::lineIndex).min().orElse(lineIndex);
                runs.add(new ChatHistoryRunSnapshot(
                        runId,
                        updatedAt,
                        queryHiddenByRunId.getOrDefault(runId, false),
                        query,
                        firstSystem,
                        latestPlan,
                        List.copyOf(allMessages),
                        List.copyOf(persistedEvents),
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
                || "artifact.publish".equals(type)
                || "run.cancel".equals(type)
                || "run.error".equals(type)
                || "run.complete".equals(type);
    }

    private String resolveRunChatId(Map<String, Object> query, List<StepEntry> steps) {
        String queryChatId = textValue(query == null ? null : query.get("chatId"));
        if (StringUtils.hasText(queryChatId)) {
            return queryChatId;
        }
        for (StepEntry step : steps) {
            if (step != null && StringUtils.hasText(step.chatId())) {
                return step.chatId().trim();
            }
        }
        return null;
    }

    private List<PersistedChatEvent> syntheticArtifactEvents(
            String runId,
            String chatId,
            List<StepEntry> steps,
            List<PersistedChatEvent> explicitPersistedEvents
    ) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Set<String> explicitArtifactIds = new HashSet<>();
        for (PersistedChatEvent event : explicitPersistedEvents) {
            if (event == null || !"artifact.publish".equals(event.type())) {
                continue;
            }
            String artifactId = textValue(event.payload().get("artifactId"));
            if (StringUtils.hasText(artifactId)) {
                explicitArtifactIds.add(artifactId.trim());
            }
        }

        Set<String> emittedArtifactIds = new HashSet<>();
        List<PersistedChatEvent> synthetic = new ArrayList<>();
        for (StepEntry step : steps) {
            if (step == null || step.artifacts() == null || step.artifacts().items == null || step.artifacts().items.isEmpty()) {
                continue;
            }
            for (ChatStorageTypes.ArtifactItemState item : step.artifacts().items) {
                if (item == null || !StringUtils.hasText(item.artifactId)) {
                    continue;
                }
                String artifactId = item.artifactId.trim();
                if (!emittedArtifactIds.add(artifactId)) {
                    continue;
                }
                if (explicitArtifactIds.contains(artifactId)) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("artifactId", artifactId);
                if (StringUtils.hasText(chatId)) {
                    payload.put("chatId", chatId);
                }
                payload.put("runId", runId);
                payload.put("artifact", compactArtifactPayload(item));
                synthetic.add(new PersistedChatEvent(
                        "artifact.publish",
                        step.updatedAt(),
                        payload,
                        step.lineIndex()
                ));
            }
        }
        return List.copyOf(synthetic);
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
}
