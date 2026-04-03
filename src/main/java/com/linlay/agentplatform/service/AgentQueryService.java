package com.linlay.agentplatform.service;

import com.linlay.agentplatform.stream.model.RunActor;
import com.linlay.agentplatform.stream.model.StreamEnvelope;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.stream.service.AgentDeltaToStreamInputMapper;
import com.linlay.agentplatform.stream.service.RenderQueue;
import com.linlay.agentplatform.stream.service.SseEventNormalizer;
import com.linlay.agentplatform.stream.service.StreamEventAssembler;
import com.linlay.agentplatform.stream.service.StreamSseStreamer;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.RuntimeContextPromptService;
import com.linlay.agentplatform.agent.RuntimeContextTags;
import com.linlay.agentplatform.agent.runtime.SandboxContextResolver;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.service.chat.ChatAssetCatalogService;
import com.linlay.agentplatform.service.chat.ChatRecordStore;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.LoggingSanitizer;
import com.linlay.agentplatform.util.RunIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.lang.Nullable;
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
    private static final Pattern REFERENCE_MARKER_PATTERN = Pattern.compile("#\\{\\{\\s*[-\\w]+\\s*:[^}]+}}");
    private static final Logger log = LoggerFactory.getLogger(AgentQueryService.class);

    private final AgentRegistry agentRegistry;
    private final StreamSseStreamer streamSseStreamer;
    private final ObjectMapper objectMapper;
    private final ChatRecordStore chatRecordStore;
    private final ToolRegistry toolRegistry;
    private final TeamRegistryService teamRegistryService;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ChatAssetCatalogService chatAssetCatalogService;
    private final ActiveRunService activeRunService;
    private final RenderQueue renderQueue;
    private final RuntimeContextPromptService runtimeContextPromptService;
    private final SseEventNormalizer sseEventNormalizer;
    private final SandboxContextResolver sandboxContextResolver;
    private final ContainerHubToolProperties containerHubToolProperties;

    @Autowired
    public AgentQueryService(
            AgentRegistry agentRegistry,
            StreamSseStreamer streamSseStreamer,
            ObjectMapper objectMapper,
            ChatRecordStore chatRecordStore,
            ToolRegistry toolRegistry,
            TeamRegistryService teamRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ChatAssetCatalogService chatAssetCatalogService,
            ActiveRunService activeRunService,
            RenderQueue renderQueue,
            RuntimeContextPromptService runtimeContextPromptService,
            SseEventNormalizer sseEventNormalizer,
            SandboxContextResolver sandboxContextResolver,
            ContainerHubToolProperties containerHubToolProperties
    ) {
        this.agentRegistry = agentRegistry;
        this.streamSseStreamer = streamSseStreamer;
        this.objectMapper = objectMapper;
        this.chatRecordStore = chatRecordStore;
        this.toolRegistry = toolRegistry;
        this.teamRegistryService = teamRegistryService;
        this.loggingAgentProperties = loggingAgentProperties;
        this.chatAssetCatalogService = chatAssetCatalogService;
        this.activeRunService = activeRunService;
        this.renderQueue = renderQueue;
        this.runtimeContextPromptService = runtimeContextPromptService;
        this.sseEventNormalizer = sseEventNormalizer;
        this.sandboxContextResolver = sandboxContextResolver;
        this.containerHubToolProperties = containerHubToolProperties == null ? new ContainerHubToolProperties() : containerHubToolProperties;
    }

    public QuerySession prepare(QueryRequest request) {
        return prepare(request, null);
    }

    public QuerySession prepare(QueryRequest request, JwksJwtVerifier.JwtPrincipal principal) {
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
        String originalMessage = request.message();
        List<QueryRequest.Reference> mergedReferences = mergeReferences(chatId, request.references());
        String effectiveMessage = buildEffectiveAgentMessage(originalMessage, mergedReferences);
        Map<String, Object> querySnapshot = buildQuerySnapshot(
                request,
                requestId,
                chatId,
                role,
                effectiveAgentKey,
                effectiveTeamId,
                mergedReferences
        );
        ChatRecordStore.ChatSummary summary = chatRecordStore.ensureChat(
                chatId,
                StringUtils.hasText(effectiveTeamId) ? null : agent.id(),
                agent.name(),
                effectiveTeamId,
                originalMessage
        );
        String chatName = summary.chatName();
        Map<String, Object> queryParams = mergeQueryParams(request.params(), summary.created());
        StreamRequest.Query streamRequest = new StreamRequest.Query(
                requestId,
                chatId,
                role,
                originalMessage,
                effectiveAgentKey,
                effectiveTeamId,
                mergedReferences.isEmpty() ? null : mergedReferences.stream().map(value -> (Object) value).toList(),
                queryParams,
                serializeScene(request.scene()),
                request.stream(),
                request.hidden(),
                chatName,
                runId,
                RunActor.primary(agent.name())
        );

        AgentRequest agentRequest = new AgentRequest(
                effectiveMessage,
                chatId,
                requestId,
                runId,
                querySnapshot,
                buildRuntimeRequestContext(
                        chatId,
                        role,
                        effectiveAgentKey,
                        effectiveTeamId,
                        chatName,
                        runId,
                        request.scene(),
                        mergedReferences,
                        principal,
                        agent
                )
        );
        return new QuerySession(agent, streamRequest, agentRequest);
    }

    AgentQueryService(
            AgentRegistry agentRegistry,
            StreamSseStreamer streamSseStreamer,
            ObjectMapper objectMapper,
            ChatRecordStore chatRecordStore,
            ToolRegistry toolRegistry,
            TeamRegistryService teamRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ChatAssetCatalogService chatAssetCatalogService,
            ActiveRunService activeRunService,
            RenderQueue renderQueue,
            SseEventNormalizer sseEventNormalizer,
            SandboxContextResolver sandboxContextResolver
    ) {
        this(
                agentRegistry,
                streamSseStreamer,
                objectMapper,
                chatRecordStore,
                toolRegistry,
                teamRegistryService,
                loggingAgentProperties,
                chatAssetCatalogService,
                activeRunService,
                renderQueue,
                new RuntimeContextPromptService(),
                sseEventNormalizer,
                sandboxContextResolver,
                new ContainerHubToolProperties()
        );
    }

    public Flux<ServerSentEvent<String>> stream(QuerySession session) {
        ActiveRunService.ActiveRunSession activeSession = activeRunService == null
                ? null
                : activeRunService.register(session.request().runId(), session.request().chatId(), session.request().agentKey());
        Flux<AgentDelta> deltas = session.agent().stream(session.agentRequest());
        Flux<StreamEnvelope> mappedInputs = new AgentDeltaToStreamInputMapper(
                session.request().runId(),
                session.request().chatId(),
                toolRegistry,
                session.request().actor()
        )
                .mapEnvelopes(deltas);
        if (activeSession != null) {
            mappedInputs = mappedInputs.takeUntilOther(activeSession.control().cancelSignal());
        }
        Flux<StreamEnvelope> inputs = activeSession == null
                ? mappedInputs
                : mappedInputs.publish(sharedInputs -> Flux.merge(
                activeSession.injectedInputs().takeUntilOther(sharedInputs.ignoreElements()),
                sharedInputs
        ));
        StringBuilder assistantContent = new StringBuilder();
        boolean[] completed = {false};
        AtomicLong eventSeq = new AtomicLong(0L);
        Set<String> hiddenToolIds = new HashSet<>();
        Flux<ServerSentEvent<String>> stream = streamSseStreamer.stream(session.request(), inputs)
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
                    } else if (("run.complete".equals(type) || "run.error".equals(type)) && !completed[0]) {
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
                .doOnNext(event -> chatRecordStore.appendEvent(
                        session.request().chatId(),
                        event.data(),
                        Boolean.TRUE.equals(session.request().hidden())
                ));
        if (activeSession != null) {
            stream = stream.doFinally(signalType -> activeRunService.finish(session.request().runId()));
        }
        if (renderQueue != null) {
            stream = renderQueue.buffer(stream);
        }
        return stream;
    }

    private ServerSentEvent<String> normalizeEvent(ServerSentEvent<String> event, Set<String> hiddenToolIds) {
        return sseEventNormalizer.normalizeEvent(event, hiddenToolIds);
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
            throw new IllegalArgumentException("agentKey is required when chat is not yet bound");
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
        return raw.trim();
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
            String teamId,
            List<QueryRequest.Reference> references
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestId", requestId);
        snapshot.put("chatId", chatId);
        snapshot.put("agentKey", effectiveAgentKey);
        snapshot.put("teamId", teamId);
        snapshot.put("role", role);
        snapshot.put("message", request.message());
        snapshot.put("references", references == null || references.isEmpty() ? null : references);
        snapshot.put("params", request.params());
        snapshot.put("scene", request.scene());
        snapshot.put("stream", request.stream());
        snapshot.put("hidden", request.hidden());
        return snapshot;
    }

    private RuntimeRequestContext buildRuntimeRequestContext(
            String chatId,
            String role,
            String effectiveAgentKey,
            String teamId,
            String chatName,
            String runId,
            QueryRequest.Scene scene,
            List<QueryRequest.Reference> references,
            JwksJwtVerifier.JwtPrincipal principal,
            Agent agent
    ) {
        AgentDefinition definition = definitionOf(agent);
        RuntimeRequestContext.LocalPaths localPaths = runtimeContextPromptService == null
                ? null
                : runtimeContextPromptService.resolveLocalPaths(chatId);
        RuntimeRequestContext.SandboxPaths sandboxPaths = runtimeContextPromptService == null
                ? null
                : runtimeContextPromptService.resolveSandboxPaths(
                        definition,
                        chatId,
                        containerHubToolProperties.getDefaultSandboxLevel()
                );
        RuntimeRequestContext.SandboxContext sandboxContext = requiresContextTag(definition, RuntimeContextTags.SANDBOX)
                ? sandboxContextResolver.resolve(definition, chatId, runId, effectiveAgentKey, teamId, chatName)
                : null;
        List<RuntimeRequestContext.AgentDigest> agentDigests = requiresContextTag(definition, RuntimeContextTags.ALL_AGENTS)
                ? buildAllAgentDigests()
                : List.of();
        return new RuntimeRequestContext(
                effectiveAgentKey,
                teamId,
                role,
                chatName,
                scene,
                references,
                principal,
                localPaths,
                sandboxPaths,
                sandboxContext,
                agentDigests
        );
    }

    private List<RuntimeRequestContext.AgentDigest> buildAllAgentDigests() {
        if (agentRegistry == null) {
            return List.of();
        }
        return agentRegistry.list().stream()
                .sorted(java.util.Comparator.comparing(Agent::id))
                .map(this::toAgentDigest)
                .toList();
    }

    private RuntimeRequestContext.AgentDigest toAgentDigest(Agent agent) {
        AgentDefinition definition = definitionOf(agent);
        List<String> tools = definition != null ? definition.tools() : (agent == null ? List.of() : agent.tools());
        List<String> skills = definition != null ? definition.skills() : (agent == null ? List.of() : agent.skills());
        RuntimeRequestContext.SandboxDigest sandbox = null;
        if (definition != null && definition.sandboxConfig() != null
                && (StringUtils.hasText(definition.sandboxConfig().environmentId()) || definition.sandboxConfig().level() != null)) {
            sandbox = new RuntimeRequestContext.SandboxDigest(
                    normalizeNullable(definition.sandboxConfig().environmentId()),
                    definition.sandboxConfig().level() == null ? null : definition.sandboxConfig().level().name()
            );
        }
        return new RuntimeRequestContext.AgentDigest(
                agent == null ? null : agent.id(),
                agent == null ? null : agent.name(),
                agent == null ? null : agent.role(),
                agent == null ? null : agent.description(),
                agent == null || agent.mode() == null ? null : agent.mode().name(),
                definition == null ? null : normalizeNullable(definition.modelKey()),
                tools,
                skills,
                sandbox
        );
    }

    private AgentDefinition definitionOf(Agent agent) {
        if (agent == null) {
            return null;
        }
        Optional<AgentDefinition> definition = agent.definition();
        return definition == null ? null : definition.orElse(null);
    }

    private boolean requiresContextTag(AgentDefinition definition, String tag) {
        return definition != null
                && definition.contextTags() != null
                && definition.contextTags().stream().anyMatch(tag::equals);
    }

    private List<QueryRequest.Reference> mergeReferences(String chatId, List<QueryRequest.Reference> references) {
        if (chatAssetCatalogService == null || !StringUtils.hasText(chatId)) {
            return references == null ? List.of() : List.copyOf(references);
        }
        try {
            return chatAssetCatalogService.mergeWithChatAssets(chatId, references);
        } catch (Exception ex) {
            log.warn("Failed to merge chat assets into query references chatId={}", chatId, ex);
            return references == null ? List.of() : List.copyOf(references);
        }
    }

    private String buildEffectiveAgentMessage(String message, List<QueryRequest.Reference> references) {
        String original = message == null ? "" : message.trim();
        if (!StringUtils.hasText(original) || references == null || references.isEmpty() || containsReferenceMarker(original)) {
            return original;
        }
        List<String> markers = references.stream()
                .filter(reference -> reference != null && StringUtils.hasText(reference.id()))
                .map(this::toReferenceMarker)
                .distinct()
                .toList();
        if (markers.isEmpty()) {
            return original;
        }
        return original + "\n\n请结合这些引用回答：" + String.join("，", markers) + "。";
    }

    private boolean containsReferenceMarker(String message) {
        return StringUtils.hasText(message) && REFERENCE_MARKER_PATTERN.matcher(message).find();
    }

    private String toReferenceMarker(QueryRequest.Reference reference) {
        String id = reference.id().trim();
        String name = StringUtils.hasText(reference.name()) ? reference.name().trim() : id;
        return "#{{" + id + ":" + name + "}}";
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
            if (StringUtils.hasText(boundAgentKey)) {
                return boundAgentKey;
            }
            if (!StringUtils.hasText(request.agentKey())) {
                throw new IllegalArgumentException("agentKey is required when chat is not yet bound");
            }
            return request.agentKey();
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
