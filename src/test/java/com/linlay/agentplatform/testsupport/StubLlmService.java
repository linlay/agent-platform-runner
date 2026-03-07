package com.linlay.agentplatform.testsupport;

import com.linlay.agentplatform.config.ChatClientRegistry;
import com.linlay.agentplatform.service.LlmCallSpec;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.stream.model.LlmDelta;
import reactor.core.publisher.Flux;

public abstract class StubLlmService extends LlmService {

    protected StubLlmService() {
        super((ChatClientRegistry) null);
    }

    @Override
    public Flux<LlmDelta> streamDeltas(LlmCallSpec spec) {
        return contentBySpec(spec).map(content -> new LlmDelta(content, null, null));
    }

    @Override
    public Flux<String> streamContent(LlmCallSpec spec) {
        return contentBySpec(spec);
    }

    protected Flux<String> contentBySpec(LlmCallSpec spec) {
        return Flux.empty();
    }
}
