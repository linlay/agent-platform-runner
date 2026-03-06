package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.stream.service.SseFlushWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.model.api.AgentListResponse;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.MarkChatReadRequest;
import com.linlay.agentplatform.model.api.MarkChatReadResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.api.SkillListResponse;
import com.linlay.agentplatform.model.api.SubmitRequest;
import com.linlay.agentplatform.model.api.SubmitResponse;
import com.linlay.agentplatform.model.api.TeamSummaryResponse;
import com.linlay.agentplatform.model.api.ToolListResponse;
import com.linlay.agentplatform.security.ApiJwtAuthWebFilter;
import com.linlay.agentplatform.security.ChatImageTokenService;
import com.linlay.agentplatform.security.JwksJwtVerifier.JwtPrincipal;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.service.AgentQueryService.QuerySession;
import com.linlay.agentplatform.service.ChatRecordStore;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LoggingSanitizer;
import com.linlay.agentplatform.service.ViewportRegistryService;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.CapabilityDescriptor;
import com.linlay.agentplatform.tool.CapabilityKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/ap")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final String SSE_EVENT_MESSAGE = "message";
    private static final String SSE_DONE_SENTINEL = "[DONE]";

    private final AgentRegistry agentRegistry;
    private final AgentQueryService agentQueryService;
    private final ChatRecordStore chatRecordStore;
    private final SseFlushWriter sseFlushWriter;
    private final ViewportRegistryService viewportRegistryService;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final ChatImageTokenService chatImageTokenService;
    private final SkillRegistryService skillRegistryService;
    private final TeamRegistryService teamRegistryService;
    private final ToolRegistry toolRegistry;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ObjectMapper objectMapper;

    public AgentController(
            AgentRegistry agentRegistry,
            AgentQueryService agentQueryService,
            ChatRecordStore chatRecordStore,
            SseFlushWriter sseFlushWriter,
            ViewportRegistryService viewportRegistryService,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            ChatImageTokenService chatImageTokenService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            ToolRegistry toolRegistry,
            LoggingAgentProperties loggingAgentProperties,
            ObjectMapper objectMapper
    ) {
        this.agentRegistry = agentRegistry;
        this.agentQueryService = agentQueryService;
        this.chatRecordStore = chatRecordStore;
        this.sseFlushWriter = sseFlushWriter;
        this.viewportRegistryService = viewportRegistryService;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
        this.chatImageTokenService = chatImageTokenService;
        this.skillRegistryService = skillRegistryService;
        this.teamRegistryService = teamRegistryService;
        this.toolRegistry = toolRegistry;
        this.loggingAgentProperties = loggingAgentProperties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentListResponse.AgentSummary>> agents(
            @RequestParam(required = false) String tag
    ) {
        List<AgentListResponse.AgentSummary> items = agentRegistry.list().stream()
                .filter(agent -> matchesTag(agent, tag))
                .map(this::toSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/teams")
    public ApiResponse<List<TeamSummaryResponse>> teams() {
        Map<String, Agent> agentsById = agentRegistry.list().stream()
                .collect(java.util.stream.Collectors.toMap(Agent::id, agent -> agent, (left, right) -> left, java.util.LinkedHashMap::new));
        List<TeamSummaryResponse> items = teamRegistryService.list().stream()
                .map(team -> toTeamSummary(team, agentsById))
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/skills")
    public ApiResponse<List<SkillListResponse.SkillSummary>> skills(
            @RequestParam(required = false) String tag
    ) {
        List<SkillListResponse.SkillSummary> items = skillRegistryService.list().stream()
                .filter(skill -> matchesSkillTag(skill, tag))
                .map(this::toSkillSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/tools")
    public ApiResponse<List<ToolListResponse.ToolSummary>> tools(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String kind
    ) {
        CapabilityKind kindFilter = parseToolKind(kind);
        List<ToolListResponse.ToolSummary> items = toolRegistry.list().stream()
                .map(this::resolveCapability)
                .filter(descriptor -> matchesToolKind(descriptor, kindFilter))
                .filter(descriptor -> matchesToolTag(descriptor, tag))
                .map(this::toToolSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/chats")
    public ApiResponse<List<ChatSummaryResponse>> chats(
            @RequestParam(required = false) String lastRunId
    ) {
        return ApiResponse.success(chatRecordStore.listChats(lastRunId));
    }

    @PostMapping("/read")
    public ApiResponse<MarkChatReadResponse> markRead(@Valid @RequestBody MarkChatReadRequest request) {
        ChatRecordStore.MarkChatReadResult result = chatRecordStore.markChatRead(request.chatId());
        return ApiResponse.success(new MarkChatReadResponse(
                result.chatId(),
                result.readStatus(),
                result.readAt()
        ));
    }

    @GetMapping("/chat")
    public ApiResponse<ChatDetailResponse> chat(
            @RequestParam String chatId,
            @RequestParam(defaultValue = "false") boolean includeRawMessages,
            ServerWebExchange exchange
    ) {
        ChatDetailResponse detail = chatRecordStore.loadChat(chatId, includeRawMessages);
        String chatImageToken = issueChatImageToken(resolvePrincipal(exchange), detail.chatId());
        return ApiResponse.success(new ChatDetailResponse(
                detail.chatId(),
                detail.chatName(),
                chatImageToken,
                detail.rawMessages(),
                detail.events(),
                detail.references()
        ));
    }

    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<Void> query(
            @Valid @RequestBody QueryRequest request,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        QuerySession session = agentQueryService.prepare(request);
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, session.request().requestId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_RUN_ID, session.request().runId());
        Map<String, Object> bodySummary = new java.util.LinkedHashMap<>();
        bodySummary.put("chatId", session.request().chatId());
        if (StringUtils.hasText(session.request().agentKey())) {
            bodySummary.put("agentKey", session.request().agentKey());
        }
        if (StringUtils.hasText(session.request().teamId())) {
            bodySummary.put("teamId", session.request().teamId());
        }
        bodySummary.put("requestId", session.request().requestId());
        bodySummary.put("runId", session.request().runId());
        if (session.request().stream() != null) {
            bodySummary.put("stream", session.request().stream());
        }
        bodySummary.put("messageChars", session.request().message() == null ? 0 : session.request().message().length());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, bodySummary);
        String chatImageToken = issueChatImageToken(resolvePrincipal(exchange), session.request().chatId());
        Flux<ServerSentEvent<String>> stream = agentQueryService.stream(session);
        if (StringUtils.hasText(chatImageToken)) {
            stream = stream.map(event -> attachChatImageTokenForChatStart(event, chatImageToken));
        }
        stream = stream.concatWith(Flux.just(ServerSentEvent.<String>builder()
                .event(SSE_EVENT_MESSAGE)
                .data(SSE_DONE_SENTINEL)
                .build()));
        return sseFlushWriter.write(response, stream);
    }

    @PostMapping("/submit")
    public ApiResponse<SubmitResponse> submit(@Valid @RequestBody SubmitRequest request, ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, request.runId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_RUN_ID, request.runId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, Map.of(
                "runId", request.runId(),
                "toolId", request.toolId(),
                "hasParams", request.params() != null
        ));
        FrontendSubmitCoordinator.SubmitAck ack = frontendSubmitCoordinator.submit(
                request.runId(),
                request.toolId(),
                request.params()
        );
        log.info(
                "Received human-in-the-loop submit runId={}, toolId={}, accepted={}, status={}",
                request.runId(),
                request.toolId(),
                ack.accepted(),
                ack.status()
        );
        return ApiResponse.success(new SubmitResponse(
                ack.accepted(),
                ack.status(),
                request.runId(),
                request.toolId(),
                ack.detail()
        ));
    }

    @GetMapping("/viewport")
    public ResponseEntity<ApiResponse<Object>> viewport(@RequestParam String viewportKey) {
        if (!StringUtils.hasText(viewportKey)) {
            throw new IllegalArgumentException("viewportKey is required");
        }
        return viewportRegistryService.find(viewportKey)
                .<ResponseEntity<ApiResponse<Object>>>map(viewport -> {
                    logViewport(viewportKey, HttpStatus.OK.value(), true);
                    Object data = viewport.payload();
                    if ("html".equalsIgnoreCase(viewport.viewportType().value())) {
                        data = Map.of("html", String.valueOf(viewport.payload()));
                    }
                    return ResponseEntity.ok(ApiResponse.success(data));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(notFoundViewport(viewportKey)));
    }

    private ApiResponse<Object> notFoundViewport(String viewportKey) {
        logViewport(viewportKey, HttpStatus.NOT_FOUND.value(), false);
        return ApiResponse.failure(
                HttpStatus.NOT_FOUND.value(),
                "Viewport not found: " + viewportKey,
                (Object) Map.of()
        );
    }

    private void logViewport(String viewportKey, int status, boolean hit) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getViewport().isEnabled()) {
            return;
        }
        log.info(
                "api.viewport key={}, hit={}, status={}",
                LoggingSanitizer.sanitizeText(viewportKey),
                hit,
                status
        );
    }

    private boolean matchesTag(Agent agent, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        String normalized = tag.toLowerCase();
        return agent.id().toLowerCase().contains(normalized)
                || agent.description().toLowerCase().contains(normalized)
                || agent.role().toLowerCase().contains(normalized)
                || agent.tools().stream().anyMatch(tool -> tool.toLowerCase().contains(normalized))
                || agent.skills().stream().anyMatch(skill -> skill.toLowerCase().contains(normalized));
    }

    private AgentListResponse.AgentSummary toSummary(Agent agent) {
        return new AgentListResponse.AgentSummary(
                agent.id(),
                agent.name(),
                agent.icon(),
                agent.description(),
                agent.role(),
                buildMeta(agent)
        );
    }

    private TeamSummaryResponse toTeamSummary(TeamDescriptor team, Map<String, Agent> agentsById) {
        List<String> invalidAgentKeys = new java.util.ArrayList<>();
        Object icon = null;
        for (String agentKey : team.agentKeys()) {
            Agent agent = agentsById.get(agentKey);
            if (agent == null) {
                invalidAgentKeys.add(agentKey);
                continue;
            }
            if (icon == null) {
                icon = agent.icon();
            }
        }
        String defaultAgentKey = team.defaultAgentKey();
        boolean defaultAgentKeyValid = StringUtils.hasText(defaultAgentKey)
                && team.agentKeys().contains(defaultAgentKey)
                && agentsById.containsKey(defaultAgentKey);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("invalidAgentKeys", List.copyOf(invalidAgentKeys));
        meta.put("defaultAgentKey", defaultAgentKey);
        meta.put("defaultAgentKeyValid", defaultAgentKeyValid);
        return new TeamSummaryResponse(
                team.id(),
                team.name(),
                icon,
                team.agentKeys(),
                meta
        );
    }

    private boolean matchesSkillTag(SkillDescriptor skill, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return skill.id().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.name().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.description().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.prompt().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private SkillListResponse.SkillSummary toSkillSummary(SkillDescriptor skill) {
        return new SkillListResponse.SkillSummary(
                skill.id(),
                skill.name(),
                skill.description(),
                buildSkillMeta(skill)
        );
    }

    private Map<String, Object> buildSkillMeta(SkillDescriptor skill) {
        return Map.of("promptTruncated", skill.promptTruncated());
    }

    private CapabilityDescriptor resolveCapability(BaseTool tool) {
        return toolRegistry.capability(tool.name()).orElseGet(() -> new CapabilityDescriptor(
                tool.name(),
                tool.description(),
                tool.afterCallHint(),
                tool.parametersSchema(),
                false,
                true,
                CapabilityKind.BACKEND,
                "function",
                null,
                "local",
                null,
                null,
                "java://builtin"
        ));
    }

    private CapabilityKind parseToolKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            return null;
        }
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        try {
            return CapabilityKind.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid kind: " + kind + ". Use backend|frontend|action");
        }
    }

    private boolean matchesToolKind(CapabilityDescriptor descriptor, CapabilityKind kindFilter) {
        if (kindFilter == null) {
            return true;
        }
        return descriptor != null && descriptor.kind() == kindFilter;
    }

    private boolean matchesToolTag(CapabilityDescriptor descriptor, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        if (descriptor == null) {
            return false;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return normalizeText(descriptor.name()).contains(normalized)
                || normalizeText(descriptor.description()).contains(normalized)
                || normalizeText(descriptor.afterCallHint()).contains(normalized)
                || normalizeText(descriptor.toolType()).contains(normalized)
                || normalizeText(descriptor.toolApi()).contains(normalized)
                || normalizeText(descriptor.viewportKey()).contains(normalized)
                || (descriptor.kind() != null && descriptor.kind().name().toLowerCase(Locale.ROOT).contains(normalized));
    }

    private ToolListResponse.ToolSummary toToolSummary(CapabilityDescriptor descriptor) {
        return new ToolListResponse.ToolSummary(
                descriptor.name(),
                descriptor.name(),
                descriptor.description(),
                buildToolMeta(descriptor)
        );
    }

    private Map<String, Object> buildToolMeta(CapabilityDescriptor descriptor) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", descriptor.kind() == null ? "" : descriptor.kind().name().toLowerCase(Locale.ROOT));
        meta.put("toolType", descriptor.toolType());
        meta.put("toolApi", descriptor.toolApi());
        meta.put("sourceType", descriptor.sourceType());
        meta.put("sourceKey", descriptor.sourceKey());
        meta.put("viewportKey", descriptor.viewportKey());
        meta.put("strict", descriptor.strict());
        return meta;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> buildMeta(Agent agent) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("model", agent.model());
        meta.put("mode", agent.mode().name());
        meta.put("tools", agent.tools());
        meta.put("skills", agent.skills());
        return meta;
    }

    private JwtPrincipal resolvePrincipal(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        Object raw = exchange.getAttribute(ApiJwtAuthWebFilter.JWT_PRINCIPAL_ATTR);
        if (raw instanceof JwtPrincipal principal) {
            return principal;
        }
        return null;
    }

    private String issueChatImageToken(JwtPrincipal principal, String chatId) {
        if (principal == null || !StringUtils.hasText(principal.subject()) || !StringUtils.hasText(chatId)) {
            return null;
        }
        return chatImageTokenService.issueToken(principal.subject(), chatId);
    }

    private ServerSentEvent<String> attachChatImageTokenForChatStart(ServerSentEvent<String> event, String chatImageToken) {
        if (event == null || !StringUtils.hasText(event.data())) {
            return event;
        }
        try {
            JsonNode root = objectMapper.readTree(event.data());
            if (!(root instanceof ObjectNode objectNode)) {
                return event;
            }
            if (!"chat.start".equals(objectNode.path("type").asText())) {
                return event;
            }
            objectNode.put("chatImageToken", chatImageToken);
            return rebuildEvent(event, objectNode);
        } catch (Exception ignored) {
            return event;
        }
    }

    private ServerSentEvent<String> rebuildEvent(ServerSentEvent<String> original, ObjectNode data) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(data.toString());
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
}
