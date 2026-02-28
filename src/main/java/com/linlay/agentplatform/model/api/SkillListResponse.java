package com.linlay.agentplatform.model.api;

import java.util.List;
import java.util.Map;

public record SkillListResponse(
        List<SkillSummary> skills
) {
    public record SkillSummary(
            String key,
            String name,
            String description,
            Map<String, Object> meta
    ) {
    }
}
