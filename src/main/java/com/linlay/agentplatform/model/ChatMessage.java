package com.linlay.agentplatform.model;

import java.util.List;

public sealed interface ChatMessage permits
        ChatMessage.SystemMsg, ChatMessage.UserMsg,
        ChatMessage.AssistantMsg, ChatMessage.ToolResultMsg {

    String text();

    String role();

    record SystemMsg(String text) implements ChatMessage {
        @Override
        public String role() { return "system"; }
    }

    record UserMsg(String text) implements ChatMessage {
        @Override
        public String role() { return "user"; }
    }

    record AssistantMsg(String text, List<ToolCall> toolCalls) implements ChatMessage {
        @Override
        public String role() { return "assistant"; }

        public AssistantMsg(String text) { this(text, List.of()); }

        public AssistantMsg {
            if (toolCalls == null) {
                toolCalls = List.of();
            }
        }

        public record ToolCall(String id, String type, String name, String arguments) {}
    }

    record ToolResultMsg(List<ToolResponse> responses) implements ChatMessage {
        @Override
        public String text() { return ""; }

        @Override
        public String role() { return "tool"; }

        public ToolResultMsg {
            if (responses == null) {
                responses = List.of();
            }
        }

        public record ToolResponse(String id, String name, String responseData) {}
    }
}
