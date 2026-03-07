package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.skill.SkillDescriptor;

import java.util.Map;

public record SkillPromptBundle(
        String catalogPrompt,
        Map<String, SkillDescriptor> resolvedSkillsById
) {
}
