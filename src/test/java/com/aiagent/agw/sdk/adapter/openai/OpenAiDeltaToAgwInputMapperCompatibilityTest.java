package com.aiagent.agw.sdk.adapter.openai;

import com.aiagent.agw.sdk.model.AgwInput;
import com.aiagent.agw.sdk.model.LlmDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiDeltaToAgwInputMapperCompatibilityTest {

    @Test
    void shouldEmitReasoningAndContentWithStableIds() {
        OpenAiDeltaToAgwInputMapper mapper = new OpenAiDeltaToAgwInputMapper();

        List<AgwInput> first = mapper.mapOrEmpty(new LlmDelta("r1", "c1", null, null));
        List<AgwInput> second = mapper.mapOrEmpty(new LlmDelta("r2", "c2", null, null));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);

        AgwInput.ReasoningDelta firstReasoning = (AgwInput.ReasoningDelta) first.get(0);
        AgwInput.ContentDelta firstContent = (AgwInput.ContentDelta) first.get(1);
        AgwInput.ReasoningDelta secondReasoning = (AgwInput.ReasoningDelta) second.get(0);
        AgwInput.ContentDelta secondContent = (AgwInput.ContentDelta) second.get(1);

        assertThat(firstReasoning.reasoningId()).isEqualTo("reasoning_1");
        assertThat(secondReasoning.reasoningId()).isEqualTo("reasoning_1");
        assertThat(firstContent.contentId()).isEqualTo("content_2");
        assertThat(secondContent.contentId()).isEqualTo("content_2");
    }

    @Test
    void shouldStillEmitRunComplete() {
        OpenAiDeltaToAgwInputMapper mapper = new OpenAiDeltaToAgwInputMapper();

        List<AgwInput> inputs = mapper.mapOrEmpty(new LlmDelta("done", null, "stop"));

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0)).isInstanceOf(AgwInput.ContentDelta.class);
        assertThat(inputs.get(1)).isInstanceOf(AgwInput.RunComplete.class);
        assertThat(((AgwInput.RunComplete) inputs.get(1)).finishReason()).isEqualTo("stop");
    }
}
