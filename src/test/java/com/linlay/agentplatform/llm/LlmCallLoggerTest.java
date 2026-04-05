package com.linlay.agentplatform.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallLoggerTest {

    @Test
    void shouldCompactTraceIdAndStageIntoUnifiedPrefix() {
        LlmCallLogger callLogger = new LlmCallLogger();

        assertThat(callLogger.compactTraceId("llm-9840e95b7867465f99b50c8851fb8e35"))
                .isEqualTo("llm:9840e95b");
        assertThat(callLogger.compactStage("agent-plan-final"))
                .isEqualTo("plan.final");
        assertThat(callLogger.compactStage("agent-oneshot-tool-first-repair-2"))
                .isEqualTo("oneshot.tool.first.repair.2");
        assertThat(callLogger.compactStage(null))
                .isEqualTo("default");
        assertThat(callLogger.message("llm-9840e95b7867465f99b50c8851fb8e35", "agent-plan-final", "delta", "content: {}"))
                .isEqualTo("[llm:9840e95b][plan.final][delta] content: {}");
    }

    @Test
    void shouldWriteCompactPrefixesForDeltaLogs() {
        LlmCallLogger callLogger = new LlmCallLogger();
        Logger logger = (Logger) LoggerFactory.getLogger(LlmCallLogger.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            StringBuilder buffer = new StringBuilder();
            callLogger.appendDeltaLog(
                    buffer,
                    new LlmDelta(
                            "thinking",
                            "final answer",
                            List.of(new ToolCallDelta("call_1", "function", "weather", "{\"city\":\"Shanghai\"}")),
                            "stop"
                    ),
                    "llm-9840e95b7867465f99b50c8851fb8e35",
                    "agent-plan-final"
            );

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertThat(buffer)
                    .hasToString("thinkingfinal answer\n[tool_call] id=call_1, name=weather, args={\"city\":\"Shanghai\"}\n[finish_reason] stop");
            assertThat(messages).contains(
                    "[llm:9840e95b][plan.final][delta] reasoning: thinking",
                    "[llm:9840e95b][plan.final][delta] content: final answer",
                    "[llm:9840e95b][plan.final][delta] tool_call id=call_1, name=weather, args={\"city\":\"Shanghai\"}",
                    "[llm:9840e95b][plan.final][delta] finish_reason=stop"
            );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void shouldWriteCompactPrefixesForInfoLogs() {
        LlmCallLogger callLogger = new LlmCallLogger();
        Logger logger = (Logger) LoggerFactory.getLogger(LlmCallLogger.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        try {
            callLogger.info(
                    logger,
                    callLogger.message("llm-9840e95b7867465f99b50c8851fb8e35", "agent-plan-generate", "raw", "{}"),
                    "{\"chunk\":1}"
            );

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .contains("[llm:9840e95b][plan.generate][raw] {\"chunk\":1}");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void shouldSummarizeRememberPromptWithoutDumpingFullChatPayload() {
        LlmCallLogger callLogger = new LlmCallLogger();
        String prompt = """
                请从以下对话快照中抽取可长期保留的记忆，返回 JSON。

                chat:
                {"chatId":"chat-1","chatName":"demo","rawMessages":[{"role":"user","content":"alpha"},{"role":"assistant","content":"beta"}],"events":[{"type":"content.snapshot","text":"gamma"}],"references":[{"id":"r1","name":"ref-1"}]}
                """;

        String normalized = callLogger.normalizePrompt("remember", prompt);

        assertThat(normalized).contains("rawMessageCount=2");
        assertThat(normalized).contains("eventCount=1");
        assertThat(normalized).contains("referenceCount=1");
        assertThat(normalized).doesNotContain("\"rawMessages\":[{\"role\":\"user\",\"content\":\"alpha\"},{\"role\":\"assistant\",\"content\":\"beta\"}]");
    }
}
