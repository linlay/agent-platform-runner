package com.linlay.agentplatform.service;

import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.service.StreamEventAssembler;
import com.linlay.agentplatform.stream.service.StreamSseStreamer;
import com.linlay.agentplatform.config.FrontendToolProperties;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 查询入口编排服务。
 * <p>
 * 负责把 API 请求组装为一次完整的运行上下文：选择 Agent、补齐 request/chat/run 标识、
 * 构建 Query 请求、并将 AgentDelta 流映射为 SSE 事件流。
 * 同时在流式发送过程中做事件规范化（如 plan.update）与会话持久化挂接。
 */
@Service
public class AgentQueryService {

    private static final String AUTO_AGENT = "auto";
    private static final String DEFAULT_AGENT = "default";
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("\"type\":\"([^\"]+)\"");
    private static final Logger log = LoggerFactory.getLogger(AgentQueryService.class);

    private final AgentRegistry agentRegistry;
    private final StreamSseStreamer streamSseStreamer;
    private final ObjectMapper objectMapper;
    private final ChatRecordStore chatRecordStore;
    private final ToolRegistry toolRegistry;
    private final ViewportRegistryService viewportRegistryService;
    private final FrontendToolProperties frontendToolProperties;
    private final TeamRegistryService teamRegistryService;
    private final LoggingAgentProperties loggingAgentProperties;

    public AgentQueryService(
            AgentRegistry agentRegistry,
            StreamSseStreamer streamSseStreamer,
            ObjectMapper objectMapper,
            ChatRecordStore chatRecordStore,
            ToolRegistry toolRegistry,
            ViewportRegistryService viewportRegistryService,
            FrontendToolProperties frontendToolProperties,
            TeamRegistryService teamRegistryService
    ) {
        this(
                agentRegistry,
                streamSseStreamer,
                objectMapper,
                chatRecordStore,
                toolRegistry,
                viewportRegistryService,
                frontendToolProperties,
                teamRegistryService,
                new LoggingAgentProperties()
        );
    }

    @Autowired
    public AgentQueryService(
            AgentRegistry agentRegistry,
            StreamSseStreamer streamSseStreamer,
            ObjectMapper objectMapper,
            ChatRecordStore chatRecordStore,
            ToolRegistry toolRegistry,
            ViewportRegistryService viewportRegistryService,
            FrontendToolProperties frontendToolProperties,
            TeamRegistryService teamRegistryService,
            LoggingAgentProperties loggingAgentProperties
    ) {
        this.agentRegistry = agentRegistry;
        this.streamSseStreamer = streamSseStreamer;
        this.objectMapper = objectMapper;
        this.chatRecordStore = chatRecordStore;
        this.toolRegistry = toolRegistry;
        this.viewportRegistryService = viewportRegistryService;
        this.frontendToolProperties = frontendToolProperties;
        this.teamRegistryService = teamRegistryService;
        this.loggingAgentProperties = loggingAgentProperties;
    }

    public QuerySession prepare(QueryRequest request) {
        String chatId = parseOrGenerateUuid(request.chatId(), "chatId");
        String boundTeamId = Optional.ofNullable(chatRecordStore.findBoundTeamId(chatId))
                .orElse(Optional.empty())
                .orElse(null);
        String boundAgentKey = Optional.ofNullable(chatRecordStore.findBoundAgentKey(chatId))
                .orElse(Optional.empty())
                .orElse(null);
        String effectiveTeamId = resolveEffectiveTeamId(request.teamId(), boundTeamId, boundAgentKey);
        Agent agent = resolveAgent(resolveEffectiveAgentKey(request, boundAgentKey, effectiveTeamId));
        String runId = RunIdGenerator.nextRunId();
        String requestId = StringUtils.hasText(request.requestId())
                ? request.requestId().trim()
                : runId;
        String role = StringUtils.hasText(request.role()) ? request.role().trim() : "user";
        String effectiveAgentKey = agent.id();
        Map<String, Object> querySnapshot = buildQuerySnapshot(request, requestId, chatId, role, effectiveAgentKey, effectiveTeamId);
        ChatRecordStore.ChatSummary summary = chatRecordStore.ensureChat(
                chatId,
                StringUtils.hasText(effectiveTeamId) ? null : agent.id(),
                agent.name(),
                effectiveTeamId,
                request.message()
        );
        String chatName = summary.chatName();
        Map<String, Object> queryParams = mergeQueryParams(request.params(), summary.created());
        StreamRequest.Query streamRequest = new StreamRequest.Query(
                requestId,
                chatId,
                role,
                request.message(),
                effectiveAgentKey,
                effectiveTeamId,
                request.references() == null ? null : request.references().stream().map(value -> (Object) value).toList(),
                queryParams,
                serializeScene(request.scene()),
                request.stream(),
                chatName,
                runId
        );

        AgentRequest agentRequest = new AgentRequest(
                request.message(),
                chatId,
                requestId,
                runId,
                querySnapshot
        );
        return new QuerySession(agent, streamRequest, agentRequest);
    }

    public Flux<ServerSentEvent<String>> stream(QuerySession session) {
        Flux<AgentDelta> deltas = session.agent().stream(session.agentRequest());
        Flux<StreamInput> inputs = new AgentDeltaToStreamInputMapper(session.request().runId(), toolRegistry).map(deltas);
        StringBuilder assistantContent = new StringBuilder();
        boolean[] completed = {false};
        AtomicLong eventSeq = new AtomicLong(0L);
        Set<String> hiddenToolIds = new HashSet<>();
        return streamSseStreamer.stream(session.request(), inputs)
                .concatMap(event -> {
                    ServerSentEvent<String> normalized = normalizeEvent(event, hiddenToolIds);
                    if (normalized != null) {
                        return Flux.just(normalized);
                    }
                    return Flux.empty();
                })
                .doOnNext(event -> {
                    if (event == null || !StringUtils.hasText(event.data())) {
                        return;
                    }
                    JsonNode node;
                    try {
                        node = objectMapper.readTree(event.data());
                    } catch (Exception ignored) {
                        return;
                    }
                    String type = node.path("type").asText(null);
                    if ("content.delta".equals(type)) {
                        String delta = node.path("delta").asText("");
                        if (StringUtils.hasText(delta)) {
                            assistantContent.append(delta);
                        }
                    } else if ("content.snapshot".equals(type)) {
                        String text = node.path("text").asText("");
                        if (StringUtils.hasText(text)) {
                            assistantContent.setLength(0);
                            assistantContent.append(text);
                        }
                    } else if ("run.complete".equals(type) && !completed[0]) {
                        completed[0] = true;
                        long completedAt = node.path("timestamp").asLong(System.currentTimeMillis());
                        String assistantText = assistantContent.toString().trim();
                        chatRecordStore.onRunCompleted(new ChatRecordStore.RunCompletion(
                                session.request().chatId(),
                                session.request().runId(),
                                StringUtils.hasText(assistantText) ? assistantText : null,
                                session.request().message(),
                                completedAt
                        ));
                    }
                })
                .doOnNext(event -> {
                    String eventType = extractEventType(event.data());
                    if (!isToolEvent(eventType)) {
                        return;
                    }
                    log.debug(
                            "stream tool event type={}, requestId={}, runId={}",
                            eventType,
                            session.request().requestId(),
                            session.request().runId()
                    );
                })
                .doOnNext(event -> logSseEvent(session, event, eventSeq.incrementAndGet()))
                .doOnNext(event -> chatRecordStore.appendEvent(session.request().chatId(), event.data()));
    }

    private ServerSentEvent<String> normalizeEvent(ServerSentEvent<String> event, Set<String> hiddenToolIds) {
        if (event == null) {
            return null;
        }

        ServerSentEvent<String> heartbeatNormalized = normalizeHeartbeatCommentEvent(event);
        if (heartbeatNormalized != event) {
            return heartbeatNormalized;
        }

        if (!StringUtils.hasText(event.data())) {
            return event;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(event.data());
        } catch (Exception ignored) {
            return event;
        }
        if (!(root instanceof ObjectNode objectNode)) {
            return event;
        }

        String type = objectNode.path("type").asText();
        if ("plan.update".equals(type)) {
            ObjectNode normalized = objectMapper.createObjectNode();
            putIfPresent(normalized, "seq", objectNode.get("seq"));
            normalized.put("type", "plan.update");
            putIfPresent(normalized, "planId", objectNode.get("planId"));
            putIfPresent(normalized, "chatId", objectNode.get("chatId"));
            putIfPresent(normalized, "plan", objectNode.get("plan"));
            putIfPresent(normalized, "timestamp", objectNode.get("timestamp"));
            return rebuildEvent(event, normalized);
        }

        if (shouldHideToolEvent(type, objectNode, hiddenToolIds)) {
            return null;
        }

        if (normalizeFrontendToolEvent(type, objectNode)) {
            return rebuildEvent(event, objectNode);
        }

        return event;
    }

    @SuppressWarnings("unused")
    private ServerSentEvent<String> normalizeEvent(ServerSentEvent<String> event) {
        return normalizeEvent(event, new HashSet<>());
    }

    private ServerSentEvent<String> normalizeHeartbeatCommentEvent(ServerSentEvent<String> event) {
        if (!StringUtils.hasText(event.comment())
                || StringUtils.hasText(event.event())
                || StringUtils.hasText(event.data())) {
            return event;
        }
        if (!"heartbeat".equals(event.comment().trim())) {
            return event;
        }

        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder();
        builder.event("heartbeat");
        if (StringUtils.hasText(event.id())) {
            builder.id(event.id());
        }
        if (event.retry() != null) {
            builder.retry(event.retry());
        }
        return builder.build();
    }

    private void putIfPresent(ObjectNode target, String key, JsonNode value) {
        if (target == null || key == null || value == null || value.isMissingNode()) {
            return;
        }
        target.set(key, value);
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private ServerSentEvent<String> rebuildEvent(ServerSentEvent<String> original, ObjectNode data) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(toJson(data));
        if (StringUtils.hasText(original.event())) {
            builder.event(original.event());
        }
        if (StringUtils.hasText(original.id())) {
            builder.id(original.id());
        }
        if (StringUtils.hasText(original.comment())) {
            builder.comment(original.comment());
        }
        if (original.retry() != null) {
            builder.retry(original.retry());
        }
        return builder.build();
    }

    private boolean normalizeFrontendToolEvent(String eventType, ObjectNode root) {
        if (!"tool.start".equals(eventType) && !"tool.snapshot".equals(eventType)) {
            return false;
        }

        String toolName = root.path("toolName").asText(null);
        if (!StringUtils.hasText(toolName)) {
            return false;
        }

        return toolRegistry.descriptor(toolName)
                .filter(com.linlay.agentplatform.tool.ToolDescriptor::hasViewport)
                .map(descriptor -> {
                    String toolKey = StringUtils.hasText(descriptor.viewportKey())
                            ? descriptor.viewportKey().trim()
                            : null;
                    if (!StringUtils.hasText(toolKey)) {
                        return false;
                    }
                    root.put("toolKey", toolKey);
                    root.put("toolType", resolveViewportToolType(descriptor.toolType(), toolKey));
                    root.put("toolTimeout", Math.max(1L, frontendToolProperties.getSubmitTimeoutMs()));
                    return true;
                })
                .orElse(false);
    }

    private boolean shouldHideToolEvent(String eventType, ObjectNode root, Set<String> hiddenToolIds) {
        if (root == null || hiddenToolIds == null || !StringUtils.hasText(eventType)) {
            return false;
        }
        if ("tool.start".equals(eventType) || "tool.snapshot".equals(eventType)) {
            String toolName = root.path("toolName").asText(null);
            String toolId = root.path("toolId").asText(null);
            boolean hidden = toolRegistry.descriptor(toolName)
                    .map(descriptor -> Boolean.FALSE.equals(descriptor.clientVisible()))
                    .orElse(false);
            if (hidden && StringUtils.hasText(toolId)) {
                hiddenToolIds.add(toolId.trim());
            }
            return hidden;
        }
        if (!eventType.startsWith("tool.")) {
            return false;
        }
        String toolId = root.path("toolId").asText(null);
        if (!StringUtils.hasText(toolId)) {
            return false;
        }
        boolean hidden = hiddenToolIds.contains(toolId.trim());
        if (hidden && "tool.result".equals(eventType)) {
            hiddenToolIds.remove(toolId.trim());
        }
        return hidden;
    }

    private String resolveViewportToolType(String descriptorToolType, String toolKey) {
        if (StringUtils.hasText(descriptorToolType)) {
            return descriptorToolType.trim();
        }
        return viewportRegistryService.find(toolKey)
                .map(viewport -> viewport.viewportType().value())
                .filter(StringUtils::hasText)
                .orElse("html");
    }

    private Map<String, Object> mergeQueryParams(Map<String, Object> requestParams, boolean created) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (requestParams != null && !requestParams.isEmpty()) {
            merged.putAll(requestParams);
        }
        merged.put(StreamEventAssembler.INTERNAL_PARAM_EMIT_CHAT_START, created);
        return merged;
    }

    private String extractEventType(String eventData) {
        if (!StringUtils.hasText(eventData)) {
            return null;
        }
        Matcher matcher = EVENT_TYPE_PATTERN.matcher(eventData);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private boolean isToolEvent(String eventType) {
        return eventType != null && eventType.startsWith("tool.");
    }

    private void logSseEvent(QuerySession session, ServerSentEvent<String> event, long seq) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getSse().isEnabled()) {
            return;
        }
        String eventType = extractEventType(event == null ? null : event.data());
        if (!StringUtils.hasText(eventType)) {
            eventType = StringUtils.hasText(event == null ? null : event.event()) ? event.event() : "unknown";
        }
        if (!allowSseEvent(eventType)) {
            return;
        }
        if (loggingAgentProperties.getSse().isIncludePayload() && event != null && StringUtils.hasText(event.data())) {
            log.info(
                    "api.sse.event seq={}, requestId={}, runId={}, eventType={}, payload={}",
                    seq,
                    session.request().requestId(),
                    session.request().runId(),
                    eventType,
                    LoggingSanitizer.sanitizeText(event.data())
            );
            return;
        }
        log.info(
                "api.sse.event seq={}, requestId={}, runId={}, eventType={}",
                seq,
                session.request().requestId(),
                session.request().runId(),
                eventType
        );
    }

    private boolean allowSseEvent(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return false;
        }
        if (loggingAgentProperties == null) {
            return true;
        }
        List<String> whitelist = loggingAgentProperties.getSse().getEventWhitelist().stream()
                .filter(StringUtils::hasText)
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (whitelist.isEmpty()) {
            return true;
        }
        String normalized = eventType.trim().toLowerCase(Locale.ROOT);
        return whitelist.stream()
                .anyMatch(normalized::equals);
    }

    private Agent resolveAgent(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return agentRegistry.defaultAgent();
        }

        String normalized = agentKey.trim();
        if (AUTO_AGENT.equalsIgnoreCase(normalized) || DEFAULT_AGENT.equalsIgnoreCase(normalized)) {
            return agentRegistry.defaultAgent();
        }
        return agentRegistry.get(normalized);
    }

    private String parseOrGenerateUuid(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID");
        }
    }

    private String serializeScene(QueryRequest.Scene scene) {
        if (scene == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(scene);
        } catch (JsonProcessingException ex) {
            return scene.toString();
        }
    }

    private Map<String, Object> buildQuerySnapshot(
            QueryRequest request,
            String requestId,
            String chatId,
            String role,
            String effectiveAgentKey,
            String teamId
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestId", requestId);
        snapshot.put("chatId", chatId);
        snapshot.put("agentKey", effectiveAgentKey);
        snapshot.put("teamId", teamId);
        snapshot.put("role", role);
        snapshot.put("message", request.message());
        snapshot.put("references", request.references());
        snapshot.put("params", request.params());
        snapshot.put("scene", request.scene());
        snapshot.put("stream", request.stream());
        return snapshot;
    }

    private String resolveEffectiveTeamId(String requestTeamId, String boundTeamId, String boundAgentKey) {
        String requested = normalizeNullable(requestTeamId);
        String bound = normalizeNullable(boundTeamId);
        if (StringUtils.hasText(requested) && StringUtils.hasText(boundAgentKey)) {
            throw new IllegalArgumentException("teamId cannot be provided for an agent-bound chat");
        }
        if (StringUtils.hasText(bound) && StringUtils.hasText(requested) && !bound.equalsIgnoreCase(requested)) {
            throw new IllegalArgumentException("teamId does not match chat binding");
        }
        return StringUtils.hasText(bound) ? bound : requested;
    }

    private String resolveEffectiveAgentKey(QueryRequest request, String boundAgentKey, String effectiveTeamId) {
        if (!StringUtils.hasText(effectiveTeamId)) {
            return StringUtils.hasText(boundAgentKey) ? boundAgentKey : request.agentKey();
        }
        if (!StringUtils.hasText(request.agentKey())) {
            throw new IllegalArgumentException("agentKey is required when teamId is provided");
        }
        TeamDescriptor team = teamRegistryService.find(effectiveTeamId)
                .orElseThrow(() -> new IllegalArgumentException("teamId not found: " + effectiveTeamId));
        String requestedAgentKey = request.agentKey().trim();
        boolean belongsToTeam = team.agentKeys().stream()
                .anyMatch(agentKey -> requestedAgentKey.equals(agentKey));
        if (!belongsToTeam) {
            throw new IllegalArgumentException("agentKey '" + requestedAgentKey + "' is not in team '" + team.id() + "'");
        }
        return requestedAgentKey;
    }

    private String normalizeNullable(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    public record QuerySession(
            Agent agent,
            StreamRequest.Query request,
            AgentRequest agentRequest
    ) {
    }
}
