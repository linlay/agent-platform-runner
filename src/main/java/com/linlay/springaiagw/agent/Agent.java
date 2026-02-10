package com.linlay.springaiagw.agent;

import com.linlay.springaiagw.model.AgentDelta;
import com.linlay.springaiagw.model.AgentRequest;
import com.linlay.springaiagw.model.ProviderType;
import reactor.core.publisher.Flux;

public interface Agent {

    String id();

    ProviderType providerType();

    String model();

    String systemPrompt();

    Flux<AgentDelta> stream(AgentRequest request);
}
