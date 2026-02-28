package com.linlay.agentplatform.model.api;

import java.util.Map;

public record SkillDetailResponse(
        SkillDetail skill
) {
    public record SkillDetail(
            String key,
            String name,
            String description,
            String instructions,
            Map<String, Object> meta
    ) {
    }
}
