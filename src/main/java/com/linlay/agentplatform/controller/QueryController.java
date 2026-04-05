package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.InterruptRequest;
import com.linlay.agentplatform.model.api.InterruptResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.api.SteerRequest;
import com.linlay.agentplatform.model.api.SteerResponse;
import com.linlay.agentplatform.model.api.SubmitRequest;
import com.linlay.agentplatform.model.api.SubmitResponse;
import com.linlay.agentplatform.security.ChatImageTokenHelper;
import com.linlay.agentplatform.engine.query.ActiveRunService;
import com.linlay.agentplatform.engine.query.AgentQueryService;
import com.linlay.agentplatform.engine.runtime.tool.FrontendSubmitCoordinator;
import com.linlay.agentplatform.stream.service.SseFlushWriter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);
    private static final String SSE_EVENT_MESSAGE = "message";
    private static final String SSE_DONE_SENTINEL = "[DONE]";

    private final AgentQueryService agentQueryService;
    private final SseFlushWriter sseFlushWriter;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final ActiveRunService activeRunService;
    private final ChatImageTokenHelper chatImageTokenHelper;
    private final ObjectMapper objectMapper;

    public QueryController(
            AgentQueryService agentQueryService,
            SseFlushWriter sseFlushWriter,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            ActiveRunService activeRunService,
            ChatImageTokenHelper chatImageTokenHelper,
            ObjectMapper objectMapper
    ) {
        this.agentQueryService = agentQueryService;
        this.sseFlushWriter = sseFlushWriter;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
        this.activeRunService = activeRunService;
        this.chatImageTokenHelper = chatImageTokenHelper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/query")
    public Mono<Void> query(
            @Valid @RequestBody QueryRequest request,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        try {
            AgentQueryService.QuerySession session = agentQueryService.prepare(
                    request,
                    chatImageTokenHelper.resolvePrincipal(exchange)
            );
            exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, session.request().requestId());
            exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_RUN_ID, session.request().runId());
            Map<String, Object> bodySummary = new LinkedHashMap<>();
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
            String chatImageToken = chatImageTokenHelper.issueChatImageToken(exchange, session.request().chatId());
            Flux<ServerSentEvent<String>> stream = agentQueryService.stream(session);
            if (StringUtils.hasText(chatImageToken)) {
                stream = stream.map(event -> attachChatImageTokenForChatStart(event, chatImageToken));
            }
            stream = stream.concatWith(Flux.just(ServerSentEvent.<String>builder()
                    .event(SSE_EVENT_MESSAGE)
                    .data(SSE_DONE_SENTINEL)
                    .build()));
            return sseFlushWriter.write(response, stream);
        } catch (IllegalArgumentException ex) {
            log.warn("Reject /api/query before SSE start: {}", ex.getMessage());
            return writeJsonFailure(response, HttpStatus.BAD_REQUEST, ApiResponse.failure(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("Fail /api/query before SSE start", ex);
            return writeJsonFailure(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiResponse.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error")
            );
        }
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
        ActiveRunService.SubmitAck ack = activeRunService.submit(request);
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

    @PostMapping("/steer")
    public ApiResponse<SteerResponse> steer(@Valid @RequestBody SteerRequest request, ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, request.requestId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_RUN_ID, request.runId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, Map.of(
                "runId", request.runId(),
                "steerId", StringUtils.hasText(request.steerId()) ? request.steerId() : "(generated)",
                "messageChars", request.message() == null ? 0 : request.message().length()
        ));
        ActiveRunService.SteerAck ack = activeRunService.steer(request);
        log.info("Received steer request runId={}, steerId={}, accepted={}, status={}",
                request.runId(), ack.steerId(), ack.accepted(), ack.status());
        return ApiResponse.success(new SteerResponse(
                ack.accepted(),
                ack.status(),
                ack.runId(),
                ack.steerId(),
                ack.detail()
        ));
    }

    @PostMapping("/interrupt")
    public ApiResponse<InterruptResponse> interrupt(@Valid @RequestBody InterruptRequest request, ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, request.requestId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_RUN_ID, request.runId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, Map.of(
                "runId", request.runId()
        ));
        ActiveRunService.InterruptAck ack = activeRunService.interrupt(request);
        log.info("Received interrupt request runId={}, accepted={}, status={}",
                request.runId(), ack.accepted(), ack.status());
        return ApiResponse.success(new InterruptResponse(
                ack.accepted(),
                ack.status(),
                ack.runId(),
                ack.detail()
        ));
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

    private Mono<Void> writeJsonFailure(
            ServerHttpResponse response,
            HttpStatus status,
            ApiResponse<?> body
    ) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
        } catch (Exception ex) {
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] fallback = """
                    {"code":500,"msg":"Internal server error","data":{}}
                    """.strip().getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback)));
        }
    }
}
