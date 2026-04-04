package com.linlay.agentplatform.model.api;

public record RememberResponse(
        boolean accepted,
        String status,
        String requestId,
        String chatId,
        String memoryPath,
        String memoryRoot,
        int memoryCount,
        String detail,
        PromptPreviewResponse promptPreview,
        java.util.List<RememberItemResponse> items,
        java.util.List<StoredMemoryResponse> stored
) {
    public record RememberItemResponse(
            String summary,
            String subjectKey
    ) {
    }

    public record StoredMemoryResponse(
            String id,
            String requestId,
            String chatId,
            String agentKey,
            String subjectKey,
            String summary,
            String sourceType,
            String category,
            int importance,
            java.util.List<String> tags,
            long createdAt,
            long updatedAt
    ) {
    }

    public record PromptPreviewResponse(
            String systemPrompt,
            String userPrompt,
            String chatName,
            int rawMessageCount,
            int eventCount,
            int referenceCount,
            java.util.List<String> rawMessageSamples,
            java.util.List<String> eventSamples,
            java.util.List<String> referenceSamples
    ) {
    }
}
