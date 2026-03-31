package com.linlay.agentplatform.stream.adapter.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.OpenAiCompatConfig;
import com.linlay.agentplatform.config.OpenAiCompatResponseConfig;
import com.linlay.agentplatform.config.ReasoningFormat;
import com.linlay.agentplatform.config.ThinkTagConfig;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiSseDeltaParserCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseReasoningContentToolCallsFinishReasonAndUsage() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(objectMapper);
        String chunk = """
                data: {"usage":{"prompt_tokens":11,"completion_tokens":7},"choices":[{"delta":{"reasoning_content":"思考","content":"答案","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"bash","arguments":"{\\"command\\":\\"ls\\"}"}}]},"finish_reason":"stop"}]}
                """;

        LlmDelta delta = parser.parseOrNull(chunk);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isEqualTo("思考");
        assertThat(delta.content()).isEqualTo("答案");
        assertThat(delta.finishReason()).isEqualTo("stop");
        assertThat(delta.usage()).containsEntry("prompt_tokens", 11L).containsEntry("completion_tokens", 7L);
        assertThat(delta.toolCalls()).hasSize(1);

        ToolCallDelta toolCall = delta.toolCalls().getFirst();
        assertThat(toolCall.id()).isEqualTo("call_1");
        assertThat(toolCall.index()).isEqualTo(0);
        assertThat(toolCall.name()).isEqualTo("bash");
        assertThat(toolCall.arguments()).isEqualTo("{\"command\":\"ls\"}");
    }

    @Test
    void shouldParseReasoningDetailsTextWhenConfigured() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(
                objectMapper,
                compat(ReasoningFormat.REASONING_DETAILS_TEXT)
        );
        String chunk = """
                data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"先分析"},{"type":"reasoning.text","text":"后回答"}],"content":"最终答案"}}]}
                """;

        LlmDelta delta = parser.parseOrNull(chunk);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isEqualTo("先分析后回答");
        assertThat(delta.content()).isEqualTo("最终答案");
    }

    @Test
    void shouldExtractThinkTagContentWhenConfigured() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(
                objectMapper,
                compat(ReasoningFormat.THINK_TAG_CONTENT)
        );
        String chunk = """
                data: {"choices":[{"delta":{"content":"<think>思考过程</think>最终答案"}}]}
                """;

        LlmDelta delta = parser.parseOrNull(chunk);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isEqualTo("思考过程");
        assertThat(delta.content()).isEqualTo("最终答案");
    }

    @Test
    void shouldHandleThinkTagSplitAcrossChunks() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(
                objectMapper,
                compat(ReasoningFormat.THINK_TAG_CONTENT)
        );

        LlmDelta first = parser.parseOrNull("""
                data: {"choices":[{"delta":{"content":"<th"}}]}
                """);
        LlmDelta second = parser.parseOrNull("""
                data: {"choices":[{"delta":{"content":"ink>思考"}}]}
                """);
        LlmDelta third = parser.parseOrNull("""
                data: {"choices":[{"delta":{"content":"过程</th"}}]}
                """);
        LlmDelta fourth = parser.parseOrNull("""
                data: {"choices":[{"delta":{"content":"ink>答案"}}]}
                """);

        assertThat(first).isNull();
        assertThat(second).isNotNull();
        assertThat(second.reasoning()).isEqualTo("思考");
        assertThat(second.content()).isNull();
        assertThat(third).isNotNull();
        assertThat(third.reasoning()).isEqualTo("过程");
        assertThat(third.content()).isNull();
        assertThat(fourth).isNotNull();
        assertThat(fourth.reasoning()).isNull();
        assertThat(fourth.content()).isEqualTo("答案");
    }

    @Test
    void shouldKeepReasoningAndVisibleContentWhenChunkContainsBoth() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(
                objectMapper,
                compat(ReasoningFormat.REASONING_DETAILS_TEXT, ReasoningFormat.THINK_TAG_CONTENT)
        );
        String chunk = """
                data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"独立推理"}],"content":"<think>标签推理</think>可见内容"}}],"usage":{"prompt_tokens":1}}
                """;

        LlmDelta delta = parser.parseOrNull(chunk);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isEqualTo("独立推理标签推理");
        assertThat(delta.content()).isEqualTo("可见内容");
        assertThat(delta.usage()).containsEntry("prompt_tokens", 1L);
    }

    @Test
    void shouldReturnDeltaWhenOnlyUsageExistsWithoutChoices() {
        OpenAiSseDeltaParser parser = new OpenAiSseDeltaParser(objectMapper);
        String chunk = """
                data: {"usage":{"prompt_tokens":3,"completion_tokens":2,"details":{"cached":1},"sources":["a","b"]}}
                """;

        LlmDelta delta = parser.parseOrNull(chunk);

        assertThat(delta).isNotNull();
        assertThat(delta.reasoning()).isNull();
        assertThat(delta.content()).isNull();
        assertThat(delta.toolCalls()).isNull();
        assertThat(delta.finishReason()).isNull();
        assertThat(delta.usage()).containsEntry("prompt_tokens", 3L);
        assertThat(delta.usage().get("details")).isEqualTo(Map.of("cached", 1L));
        assertThat(delta.usage().get("sources")).isEqualTo(List.of("a", "b"));
    }

    private OpenAiCompatConfig compat(ReasoningFormat... formats) {
        return new OpenAiCompatConfig(
                null,
                new OpenAiCompatResponseConfig(
                        List.of(formats),
                        new ThinkTagConfig("<think>", "</think>", true)
                )
        );
    }
}
