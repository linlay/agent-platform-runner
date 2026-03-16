package com.linlay.agentplatform.stream.model;

import java.util.Objects;

public record StreamEnvelope(
        StreamInput input,
        RunActor actor
) {

    public StreamEnvelope {
        input = Objects.requireNonNull(input, "input must not be null");
    }

    public static StreamEnvelope of(StreamInput input, RunActor actor) {
        return new StreamEnvelope(input, actor);
    }
}
