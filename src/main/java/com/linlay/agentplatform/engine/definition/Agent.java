package com.linlay.agentplatform.engine.definition;

import com.linlay.agentplatform.engine.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.AgentRequest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

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

    default List<AgentControl> controls() {
        return definition().map(AgentDefinition::controls).orElse(List.of());
    }

    default Optional<AgentDefinition> definition() {
        return Optional.empty();
    }

    Flux<AgentDelta> stream(AgentRequest request);
}
