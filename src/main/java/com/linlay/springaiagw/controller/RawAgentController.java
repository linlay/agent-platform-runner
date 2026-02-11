package com.linlay.springaiagw.controller;

import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.service.RawAgentSseStreamService;
import com.linlay.springaiagw.service.SseFlushWriter;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/raw-api")
public class RawAgentController {

    private final RawAgentSseStreamService rawAgentSseStreamService;
    private final SseFlushWriter sseFlushWriter;

    public RawAgentController(RawAgentSseStreamService rawAgentSseStreamService, SseFlushWriter sseFlushWriter) {
        this.rawAgentSseStreamService = rawAgentSseStreamService;
        this.sseFlushWriter = sseFlushWriter;
    }

    @PostMapping(value = "/{agentId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<Void> stream(
            @PathVariable String agentId,
            @Valid @RequestBody AgentRequest request,
            ServerHttpResponse response
    ) {
        Flux<ServerSentEvent<String>> stream = rawAgentSseStreamService.stream(agentId, request);
        return sseFlushWriter.write(response, stream);
    }
}
