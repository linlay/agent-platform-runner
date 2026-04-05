package com.linlay.agentplatform.catalog.team;

import java.util.List;

public record TeamDescriptor(
        String id,
        String name,
        List<String> agentKeys,
        String defaultAgentKey,
        String sourceFile
) {
}
