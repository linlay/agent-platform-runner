package com.linlay.springaiagw.controller;

import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.service.AgwSseStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agw-agent")
public class AgwAgentController {

    private final AgwSseStreamService agwSseStreamService;

    public AgwAgentController(AgwSseStreamService agwSseStreamService) {
        this.agwSseStreamService = agwSseStreamService;
    }

    @PostMapping(value = "/{agentId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId,
            @Valid @RequestBody AgentRequest request
    ) {
        return agwSseStreamService.stream(agentId, request);
    }
}
