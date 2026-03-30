package com.linlay.agentplatform.agent.mode;

import com.linlay.agentplatform.agent.AgentConfigFile;
import com.linlay.agentplatform.agent.SkillAppend;
import com.linlay.agentplatform.agent.ToolAppend;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OneshotModeTest {

    @Test
    void defaultRunSpec_noBudgetConfig_returnsBudgetDefault() {
        StageSettings stage = new StageSettings("prompt", null, null, List.of("some_tool"), false, null);
        OneshotMode mode = new OneshotMode(stage, SkillAppend.DEFAULTS, ToolAppend.DEFAULTS);

        RunSpec spec = mode.defaultRunSpec(new AgentConfigFile());

        assertThat(spec.budget()).isEqualTo(Budget.DEFAULT);
        assertThat(spec.budget().runTimeoutMs()).isEqualTo(300_000L);
        assertThat(spec.budget().model().maxCalls()).isEqualTo(30);
        assertThat(spec.budget().model().timeoutMs()).isEqualTo(120_000L);
    }

    @Test
    void defaultRunSpec_nullConfig_returnsBudgetDefault() {
        StageSettings stage = new StageSettings("prompt", null, null, List.of(), false, null);
        OneshotMode mode = new OneshotMode(stage, SkillAppend.DEFAULTS, ToolAppend.DEFAULTS);

        RunSpec spec = mode.defaultRunSpec(null);

        assertThat(spec.budget()).isEqualTo(Budget.DEFAULT);
    }

    @Test
    void defaultRunSpec_withTools_toolChoiceAuto() {
        StageSettings stage = new StageSettings("prompt", null, null, List.of("tool_a"), false, null);
        OneshotMode mode = new OneshotMode(stage, SkillAppend.DEFAULTS, ToolAppend.DEFAULTS);

        RunSpec spec = mode.defaultRunSpec(new AgentConfigFile());

        assertThat(spec.toolChoice()).isEqualTo(ToolChoice.AUTO);
    }

    @Test
    void defaultRunSpec_noTools_toolChoiceNone() {
        StageSettings stage = new StageSettings("prompt", null, null, List.of(), false, null);
        OneshotMode mode = new OneshotMode(stage, SkillAppend.DEFAULTS, ToolAppend.DEFAULTS);

        RunSpec spec = mode.defaultRunSpec(new AgentConfigFile());

        assertThat(spec.toolChoice()).isEqualTo(ToolChoice.NONE);
    }
}
