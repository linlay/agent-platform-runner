package com.linlay.springaiagw.controller;

import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.service.OpenAiSseStreamService;
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
@RequestMapping("/api/agent")
public class AgentController {

    private final OpenAiSseStreamService openAiSseStreamService;

    public AgentController(OpenAiSseStreamService openAiSseStreamService) {
        this.openAiSseStreamService = openAiSseStreamService;
    }

    @PostMapping(value = "/{agentId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId,
            @Valid @RequestBody AgentRequest request
    ) {
        return openAiSseStreamService.stream(agentId, request);
    }
}
