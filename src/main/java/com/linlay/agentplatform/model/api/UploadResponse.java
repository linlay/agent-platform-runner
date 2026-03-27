package com.linlay.agentplatform.model.api;

public record UploadResponse(
        String requestId,
        String chatId,
        UploadTicket upload
) {
    public record UploadTicket(
            String id,
            String type,
            String name,
            String mimeType,
            Long sizeBytes,
            String url,
            String sha256
    ) {
    }
}
