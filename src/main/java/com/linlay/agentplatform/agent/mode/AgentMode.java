package com.linlay.agentplatform.agent.mode;

import com.linlay.agentplatform.agent.AgentConfigFile;
import com.linlay.agentplatform.agent.SkillAppend;
import com.linlay.agentplatform.agent.ToolAppend;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.tool.BaseTool;
import reactor.core.publisher.FluxSink;

import java.util.Map;

public sealed abstract class AgentMode
        permits OneshotMode, ReactMode, PlanExecuteMode {

    protected final String systemPrompt;
    protected final SkillAppend skillAppend;
    protected final ToolAppend toolAppend;

    protected AgentMode(String systemPrompt, SkillAppend skillAppend, ToolAppend toolAppend) {
        this.systemPrompt = systemPrompt;
        this.skillAppend = skillAppend == null ? SkillAppend.DEFAULTS : skillAppend;
        this.toolAppend = toolAppend == null ? ToolAppend.DEFAULTS : toolAppend;
    }

    public abstract AgentRuntimeMode runtimeMode();

    public String systemPrompt() {
        return systemPrompt;
    }

    public String primarySystemPrompt() {
        return systemPrompt;
    }

    public SkillAppend skillAppend() {
        return skillAppend;
    }

    public ToolAppend toolAppend() {
        return toolAppend;
    }

    public abstract RunSpec defaultRunSpec(AgentConfigFile config);

    public abstract void run(
            ExecutionContext context,
            Map<String, BaseTool> enabledToolsByName,
            OrchestratorServices services,
            FluxSink<AgentDelta> sink
    );
}
