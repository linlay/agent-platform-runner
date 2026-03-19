package com.linlay.agentplatform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.model.api.AgentDetailResponse;
import com.linlay.agentplatform.model.api.AgentListResponse;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.InterruptRequest;
import com.linlay.agentplatform.model.api.InterruptResponse;
import com.linlay.agentplatform.model.api.MarkChatReadRequest;
import com.linlay.agentplatform.model.api.MarkChatReadResponse;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.api.SteerRequest;
import com.linlay.agentplatform.model.api.SteerResponse;
import com.linlay.agentplatform.model.api.SkillListResponse;
import com.linlay.agentplatform.model.api.SubmitRequest;
import com.linlay.agentplatform.model.api.SubmitResponse;
import com.linlay.agentplatform.model.api.TeamSummaryResponse;
import com.linlay.agentplatform.model.api.ToolDetailResponse;
import com.linlay.agentplatform.model.api.ToolListResponse;
import com.linlay.agentplatform.security.ChatImageTokenService;
import com.linlay.agentplatform.service.ActiveRunService;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.service.ChatRecordStore;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.McpViewportService;
import com.linlay.agentplatform.service.ViewportRegistryService;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.stream.service.SseFlushWriter;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

public class AgentController {

    private final AgentCatalogController agentCatalogController;
    private final ChatController chatController;
    private final QueryController queryController;
    private final ViewportController viewportController;

    public AgentController(
            AgentRegistry agentRegistry,
            AgentQueryService agentQueryService,
            ChatRecordStore chatRecordStore,
            SseFlushWriter sseFlushWriter,
            ViewportRegistryService viewportRegistryService,
            McpViewportService mcpViewportService,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            ActiveRunService activeRunService,
            ChatImageTokenService chatImageTokenService,
            SkillRegistryService skillRegistryService,
            TeamRegistryService teamRegistryService,
            ToolRegistry toolRegistry,
            LoggingAgentProperties loggingAgentProperties,
            ObjectMapper objectMapper
    ) {
        ChatImageTokenHelper chatImageTokenHelper = new ChatImageTokenHelper(chatImageTokenService);
        this.agentCatalogController = new AgentCatalogController(
                agentRegistry,
                teamRegistryService,
                skillRegistryService,
                toolRegistry
        );
        this.chatController = new ChatController(chatRecordStore, chatImageTokenHelper);
        this.queryController = new QueryController(
                agentQueryService,
                sseFlushWriter,
                frontendSubmitCoordinator,
                activeRunService,
                chatImageTokenHelper,
                objectMapper
        );
        this.viewportController = new ViewportController(viewportRegistryService, mcpViewportService, loggingAgentProperties);
    }

    public ApiResponse<List<AgentListResponse.AgentSummary>> agents(String tag) {
        return agentCatalogController.agents(tag);
    }

    public ApiResponse<AgentDetailResponse> agent(String agentKey) {
        return agentCatalogController.agent(agentKey);
    }

    public ApiResponse<List<TeamSummaryResponse>> teams() {
        return agentCatalogController.teams();
    }

    public ApiResponse<List<SkillListResponse.SkillSummary>> skills(String tag) {
        return agentCatalogController.skills(tag);
    }

    public ApiResponse<List<ToolListResponse.ToolSummary>> tools(String tag, String kind) {
        return agentCatalogController.tools(tag, kind);
    }

    public ApiResponse<ToolDetailResponse> tool(String toolName) {
        return agentCatalogController.tool(toolName);
    }

    public ApiResponse<List<ChatSummaryResponse>> chats(String lastRunId, String agentKey) {
        return chatController.chats(lastRunId, agentKey);
    }

    public ApiResponse<MarkChatReadResponse> markRead(@Valid MarkChatReadRequest request) {
        return chatController.markRead(request);
    }

    public ApiResponse<ChatDetailResponse> chat(
            String chatId,
            boolean includeRawMessages,
            ServerWebExchange exchange
    ) {
        return chatController.chat(chatId, includeRawMessages, exchange);
    }

    public Mono<Void> query(
            @Valid QueryRequest request,
            ServerHttpResponse response,
            ServerWebExchange exchange
    ) {
        return queryController.query(request, response, exchange);
    }

    public ApiResponse<SubmitResponse> submit(@Valid SubmitRequest request, ServerWebExchange exchange) {
        return queryController.submit(request, exchange);
    }

    public ApiResponse<SteerResponse> steer(@Valid SteerRequest request, ServerWebExchange exchange) {
        return queryController.steer(request, exchange);
    }

    public ApiResponse<InterruptResponse> interrupt(@Valid InterruptRequest request, ServerWebExchange exchange) {
        return queryController.interrupt(request, exchange);
    }

    public Mono<ResponseEntity<ApiResponse<Object>>> viewport(String viewportKey) {
        return viewportController.viewport(viewportKey);
    }
}
