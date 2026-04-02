package com.linlay.agentplatform.chatstorage;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracted persistent chat storage DTOs shared by storage, replay, and tests.
 */
public final class ChatStorageTypes {

    private ChatStorageTypes() {
    }

    public record RunMessage(
            String role,
            String kind,
            String text,
            String name,
            String toolCallId,
            String toolCallType,
            String toolArgs,
            String reasoningId,
            String contentId,
            String msgId,
            Long ts,
            Long timing,
            Map<String, Object> usage
    ) {

        public static RunMessage user(String content) {
            return new RunMessage("user", "user_content", content, null, null, null, null, null, null, null, null, null, null);
        }

        public static RunMessage user(String content, Long ts) {
            return new RunMessage("user", "user_content", content, null, null, null, null, null, null, null, ts, null, null);
        }

        public static RunMessage assistantReasoning(String content, Long ts, Long timing, Map<String, Object> usage) {
            return assistantReasoning(content, null, null, ts, timing, usage);
        }

        public static RunMessage assistantReasoning(String content, String msgId, Long ts, Long timing, Map<String, Object> usage) {
            return assistantReasoning(content, null, msgId, ts, timing, usage);
        }

        public static RunMessage assistantReasoning(
                String content,
                String reasoningId,
                String msgId,
                Long ts,
                Long timing,
                Map<String, Object> usage
        ) {
            return new RunMessage("assistant", "assistant_reasoning", content, null, null, null, null, reasoningId, null, msgId, ts, timing, usage);
        }

        public static RunMessage assistantContent(String content) {
            return assistantContent(content, null, null, null, null, null);
        }

        public static RunMessage assistantContent(String content, Long ts, Long timing, Map<String, Object> usage) {
            return assistantContent(content, null, null, ts, timing, usage);
        }

        public static RunMessage assistantContent(String content, String msgId, Long ts, Long timing, Map<String, Object> usage) {
            return assistantContent(content, null, msgId, ts, timing, usage);
        }

        public static RunMessage assistantContent(
                String content,
                String contentId,
                String msgId,
                Long ts,
                Long timing,
                Map<String, Object> usage
        ) {
            return new RunMessage("assistant", "assistant_content", content, null, null, null, null, null, contentId, msgId, ts, timing, usage);
        }

        public static RunMessage assistantToolCall(String toolName, String toolCallId, String toolArgs) {
            return assistantToolCall(toolName, toolCallId, "function", toolArgs, null, null, null, null);
        }

        public static RunMessage assistantToolCall(
                String toolName,
                String toolCallId,
                String toolArgs,
                Long ts,
                Long timing,
                Map<String, Object> usage
        ) {
            return assistantToolCall(toolName, toolCallId, "function", toolArgs, null, ts, timing, usage);
        }

        public static RunMessage assistantToolCall(
                String toolName,
                String toolCallId,
                String toolCallType,
                String toolArgs,
                Long ts,
                Long timing,
                Map<String, Object> usage
        ) {
            return new RunMessage("assistant", "assistant_tool_call", null, toolName, toolCallId, toolCallType, toolArgs, null, null, null, ts, timing, usage);
        }

        public static RunMessage assistantToolCall(
                String toolName,
                String toolCallId,
                String toolCallType,
                String toolArgs,
                String msgId,
                Long ts,
                Long timing,
                Map<String, Object> usage
        ) {
            return new RunMessage("assistant", "assistant_tool_call", null, toolName, toolCallId, toolCallType, toolArgs, null, null, msgId, ts, timing, usage);
        }

        public static RunMessage toolResult(String toolName, String toolCallId, String toolArgs, String toolResult) {
            return new RunMessage("tool", "tool_result", toolResult, toolName, toolCallId, null, toolArgs, null, null, null, null, null, null);
        }

        public static RunMessage toolResult(
                String toolName,
                String toolCallId,
                String toolResult,
                Long ts,
                Long timing
        ) {
            return new RunMessage("tool", "tool_result", toolResult, toolName, toolCallId, null, null, null, null, null, ts, timing, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryLine {
        @JsonProperty("_type")
        public String type = "query";
        public String chatId;
        public String runId;
        public long updatedAt;
        public Boolean hidden;
        public Map<String, Object> query = new LinkedHashMap<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepLine {
        @JsonProperty("_type")
        public String type = "step";
        public String chatId;
        public String runId;
        @JsonProperty("_stage")
        public String stage;
        @JsonProperty("_seq")
        public int seq;
        public String taskId;
        public long updatedAt;
        public SystemSnapshot system;
        @JsonProperty("plan")
        public PlanState plan;
        @JsonProperty("artifacts")
        public ArtifactState artifacts;
        public List<StoredMessage> messages = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SystemSnapshot {
        public String model;
        public List<SystemMessageSnapshot> messages;
        public List<SystemToolSnapshot> tools;
        public Boolean stream;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SystemMessageSnapshot {
        public String role;
        public String content;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SystemToolSnapshot {
        public String type;
        public String name;
        public String description;
        public Map<String, Object> parameters;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanState {
        public String planId;
        @JsonProperty("tasks")
        public List<PlanTaskState> tasks;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ArtifactState {
        @JsonProperty("items")
        public List<ArtifactItemState> items;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ArtifactItemState {
        public String artifactId;
        public String type;
        public String name;
        public String mimeType;
        public Long sizeBytes;
        public String url;
        public String sha256;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanTaskState {
        public String taskId;
        public String description;
        public String status;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredMessage {
        public String role;
        public List<ContentPart> content;
        @JsonProperty("reasoning_content")
        public List<ContentPart> reasoningContent;
        @JsonProperty("tool_calls")
        public List<StoredToolCall> toolCalls;
        public Long ts;
        public String name;
        @JsonProperty("tool_call_id")
        public String toolCallId;
        @JsonProperty("_reasoningId")
        public String reasoningId;
        @JsonProperty("_contentId")
        public String contentId;
        @JsonProperty("_msgId")
        public String msgId;
        @JsonProperty("_toolId")
        public String toolId;
        @JsonProperty("_actionId")
        public String actionId;
        @JsonProperty("_timing")
        public Long timing;
        @JsonProperty("_usage")
        public Map<String, Object> usage;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentPart {
        public String type;
        public String text;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredToolCall {
        public String id;
        public String type;
        public FunctionCall function;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        public String name;
        public String arguments;
    }
}

sealed interface ParsedLine permits ParsedQueryLine, ParsedStepLine {
    String runId();
}

record ParsedQueryLine(
        String chatId,
        String runId,
        long updatedAt,
        boolean hidden,
        Map<String, Object> query
) implements ParsedLine {
}

record ParsedStepLine(
        String chatId,
        String runId,
        String stage,
        int seq,
        String taskId,
        long updatedAt,
        ChatStorageTypes.SystemSnapshot system,
        ChatStorageTypes.PlanState plan,
        ChatStorageTypes.ArtifactState artifacts,
        List<ChatStorageTypes.StoredMessage> messages
) implements ParsedLine {
}

record TextBlockSequenceState(
        int nextReasoningSeq,
        int nextContentSeq
) {
}

record ToolIdentity(String id, boolean action) {
}
