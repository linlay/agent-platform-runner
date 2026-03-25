package com.linlay.agentplatform.model.api;

import java.util.Map;

public record UploadResponse(
        String requestId,
        String chatId,
        QueryRequest.Reference reference,
        UploadTarget upload,
        Long expiresAt
) {
    public record UploadTarget(
            String url,
            String method,
            Map<String, String> headers
    ) {
    }
}
