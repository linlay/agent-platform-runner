package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import reactor.core.publisher.Flux;

import java.util.List;

public interface Agent {

    String id();

    default String name() {
        return id();
    }

    default Object icon() {
        return null;
    }

    default String description() {
        return id();
    }

    default String role() {
        return name();
    }

    String providerKey();

    String model();

    String systemPrompt();

    default AgentRuntimeMode mode() {
        return AgentRuntimeMode.ONESHOT;
    }

    default List<String> tools() {
        return List.of();
    }

    default List<String> skills() {
        return List.of();
    }

    Flux<AgentDelta> stream(AgentRequest request);
}
