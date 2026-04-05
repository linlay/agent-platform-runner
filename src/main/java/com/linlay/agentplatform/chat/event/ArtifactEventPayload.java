package com.linlay.agentplatform.chat.event;

import com.linlay.agentplatform.model.api.QueryRequest;

public record ArtifactEventPayload(
        String type,
        String name,
        String mimeType,
        Long sizeBytes,
        String url,
        String sha256
) {

    public static ArtifactEventPayload fromReference(QueryRequest.Reference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        return new ArtifactEventPayload(
                reference.type(),
                reference.name(),
                reference.mimeType(),
                reference.sizeBytes(),
                reference.url(),
                reference.sha256()
        );
    }
}
