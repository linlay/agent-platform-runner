package com.linlay.agentplatform.team;

import java.util.List;

public record TeamDescriptor(
        String id,
        String name,
        List<String> agentKeys,
        String sourceFile
) {
}
