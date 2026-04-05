package com.linlay.agentplatform.engine.runtime;

import com.linlay.agentplatform.catalog.skill.SkillDescriptor;

import java.util.Map;

public record SkillPromptBundle(
        String catalogPrompt,
        Map<String, SkillDescriptor> resolvedSkillsById
) {
}
