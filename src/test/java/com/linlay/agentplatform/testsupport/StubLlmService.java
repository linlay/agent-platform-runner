package com.linlay.agentplatform.testsupport;

import com.linlay.agentplatform.service.llm.LlmCallSpec;
import com.linlay.agentplatform.service.llm.LlmService;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.stream.model.LlmDelta;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public abstract class StubLlmService extends LlmService {

    protected StubLlmService() {
        super();
    }

    @Override
    public Flux<LlmDelta> streamDeltas(LlmCallSpec spec) {
        return contentBySpec(spec).map(content -> new LlmDelta(content, null, null));
    }

    @Override
    public Flux<String> streamContent(LlmCallSpec spec) {
        return contentBySpec(spec);
    }

    @Override
    public Mono<String> completeText(
            String modelKey,
            String providerKey,
            String model,
            ModelProtocol protocol,
            String systemPrompt,
            String userPrompt,
            String stage
    ) {
        return completeText(providerKey, model, systemPrompt, userPrompt, stage);
    }

    protected Flux<String> contentBySpec(LlmCallSpec spec) {
        return Flux.empty();
    }
}
