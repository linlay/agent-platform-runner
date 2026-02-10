package com.linlay.springaiagw.service;

import com.aiagent.agw.sdk.model.AgwDelta;
import com.aiagent.agw.sdk.model.AgwRequestContext;
import com.aiagent.agw.sdk.service.AgwSseStreamer;
import com.linlay.springaiagw.agent.Agent;
import com.linlay.springaiagw.agent.AgentRegistry;
import com.linlay.springaiagw.model.AgentDelta.ToolResult;
import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.model.SseChunk;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class AgwSseStreamService {

    private final AgentRegistry agentRegistry;
    private final AgwSseStreamer agwSseStreamer;

    public AgwSseStreamService(
            AgentRegistry agentRegistry,
            AgwSseStreamer agwSseStreamer
    ) {
        this.agentRegistry = agentRegistry;
        this.agwSseStreamer = agwSseStreamer;
    }

    public Flux<ServerSentEvent<String>> stream(String agentId, AgentRequest request) {
        Agent agent = agentRegistry.get(agentId);

        String chatId = (request.chatId() == null || request.chatId().isBlank())
                ? agwSseStreamer.generateId("chat")
                : request.chatId();
        String requestId = (request.requestId() == null || request.requestId().isBlank())
                ? agwSseStreamer.generateId("req")
                : request.requestId();
        String runId = agwSseStreamer.generateId("run");
        String chatName = (request.chatName() == null || request.chatName().isBlank())
                ? chatId
                : request.chatName();

        AgwRequestContext context = new AgwRequestContext(
                request.message(),
                chatId,
                chatName,
                requestId,
                runId
        );

        Flux<AgwDelta> deltas = agent.stream(request).map(this::toAgwDelta);
        return agwSseStreamer.stream(context, deltas);
    }

    private AgwDelta toAgwDelta(com.linlay.springaiagw.model.AgentDelta delta) {
        List<AgwDelta.ToolCall> toolCalls = delta.toolCalls() == null ? null : delta.toolCalls().stream()
                .map(this::toToolCall)
                .toList();
        List<AgwDelta.ToolResult> toolResults = delta.toolResults() == null ? null : delta.toolResults().stream()
                .map(this::toToolResult)
                .toList();

        return new AgwDelta(
                delta.content(),
                delta.thinking(),
                toolCalls,
                toolResults,
                delta.finishReason()
        );
    }

    private AgwDelta.ToolCall toToolCall(SseChunk.ToolCall toolCall) {
        String toolName = toolCall.function() == null ? null : toolCall.function().name();
        String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
        return new AgwDelta.ToolCall(toolCall.id(), toolCall.type(), toolName, arguments);
    }

    private AgwDelta.ToolResult toToolResult(ToolResult toolResult) {
        return new AgwDelta.ToolResult(toolResult.toolId(), toolResult.result());
    }
}
