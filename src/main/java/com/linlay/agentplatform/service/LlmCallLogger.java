package com.linlay.agentplatform.service;

import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.linlay.agentplatform.config.LlmInteractionLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * LLM 调用日志工具：traceId 生成、delta 日志追加、耗时计算。
 */
class LlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogger.class);
    private static final Pattern STAGE_TOKEN_SPLITTER = Pattern.compile("[^a-zA-Z0-9]+");

    private final boolean enabled;
    private final boolean maskSensitive;

    LlmCallLogger() {
        this.enabled = true;
        this.maskSensitive = true;
    }

    LlmCallLogger(LlmInteractionLogProperties properties) {
        this.enabled = properties == null || properties.isEnabled();
        this.maskSensitive = properties == null || properties.isMaskSensitive();
    }

    boolean isEnabled() {
        return enabled;
    }

    String generateTraceId() {
        return "llm-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    String normalizePrompt(String prompt) {
        return sanitizeText(prompt == null ? "" : prompt);
    }

    String sanitizeText(String text) {
        return LlmLogSanitizer.maskText(text, maskSensitive);
    }

    String context(String traceId, String stage) {
        return "[" + compactTraceId(traceId) + "][" + compactStage(stage) + "]";
    }

    String context(String traceId, String stage, String tag) {
        if (!StringUtils.hasText(tag)) {
            return context(traceId, stage);
        }
        return context(traceId, stage) + "[" + tag.trim() + "]";
    }

    String message(String traceId, String stage, String pattern) {
        return context(traceId, stage) + " " + pattern;
    }

    String message(String traceId, String stage, String tag, String pattern) {
        return context(traceId, stage, tag) + " " + pattern;
    }

    void info(Logger logger, String pattern, Object... arguments) {
        if (enabled) {
            logger.info(pattern, arguments);
        }
    }

    void debug(Logger logger, String pattern, Object... arguments) {
        if (enabled) {
            logger.debug(pattern, arguments);
        }
    }

    void logHistoryMessages(Logger logger, String traceId, String stage, List<ChatMessage> historyMessages) {
        if (!enabled || historyMessages == null || historyMessages.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < historyMessages.size(); i++) {
            ChatMessage message = historyMessages.get(i);
            if (message == null) {
                continue;
            }
            builder.append('[').append(i).append("] role=")
                    .append(message.role())
                    .append(", text=")
                    .append(sanitizeText(message.text()));

            if (message instanceof ChatMessage.AssistantMsg assistantMsg
                    && assistantMsg.toolCalls() != null
                    && !assistantMsg.toolCalls().isEmpty()) {
                builder.append(", toolCalls=").append(assistantMsg.toolCalls().size());
            }
            if (message instanceof ChatMessage.ToolResultMsg toolResultMsg
                    && toolResultMsg.responses() != null
                    && !toolResultMsg.responses().isEmpty()) {
                builder.append(", toolResponses=").append(toolResultMsg.responses().size());
            }
            builder.append('\n');
        }
        if (builder.length() == 0) {
            return;
        }
        logger.info(message(traceId, stage, "LLM stream history messages detail:\n{}"), builder);
    }

    void appendDeltaLog(StringBuilder buffer, LlmDelta delta, String traceId, String stage) {
        if (!enabled || delta == null) {
            return;
        }
        if (delta.reasoning() != null && !delta.reasoning().isEmpty()) {
            String reasoning = sanitizeText(delta.reasoning());
            buffer.append(reasoning);
            log.debug(message(traceId, stage, "delta", "reasoning: {}"), reasoning);
        }
        if (delta.content() != null && !delta.content().isEmpty()) {
            String content = sanitizeText(delta.content());
            buffer.append(content);
            log.debug(message(traceId, stage, "delta", "content: {}"), content);
        }
        if (delta.toolCalls() != null && !delta.toolCalls().isEmpty()) {
            for (ToolCallDelta call : delta.toolCalls()) {
                if (call == null) {
                    continue;
                }
                String toolId = call.id() == null ? "" : call.id();
                String toolName = call.name() == null ? "" : call.name();
                String arguments = sanitizeText(call.arguments());
                buffer.append("\n[tool_call] id=").append(call.id())
                        .append(", name=").append(toolName)
                        .append(", args=").append(arguments);
                log.debug(message(traceId, stage, "delta", "tool_call id={}, name={}, args={}"),
                        toolId, toolName, arguments);
            }
        }
        if (StringUtils.hasText(delta.finishReason())) {
            String finishReason = sanitizeText(delta.finishReason());
            buffer.append("\n[finish_reason] ").append(finishReason);
            log.debug(message(traceId, stage, "delta", "finish_reason={}"), finishReason);
        }
    }

    String compactTraceId(String traceId) {
        String raw = StringUtils.hasText(traceId) ? traceId.trim() : "unknown";
        if (raw.startsWith("llm-")) {
            raw = raw.substring(4);
        }
        if (raw.length() > 8) {
            raw = raw.substring(0, 8);
        }
        return "llm:" + raw;
    }

    String compactStage(String stage) {
        if (!StringUtils.hasText(stage)) {
            return "default";
        }
        String[] rawTokens = STAGE_TOKEN_SPLITTER.split(stage.trim().toLowerCase(Locale.ROOT));
        StringBuilder builder = new StringBuilder();
        for (String token : rawTokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (builder.length() == 0 && "agent".equals(token)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(token);
        }
        return builder.length() == 0 ? "default" : builder.toString();
    }
}
