package com.linlay.agentplatform.model;

import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;

import java.util.List;

public record RuntimeRequestContext(
        String agentKey,
        String teamId,
        String role,
        String chatName,
        QueryRequest.Scene scene,
        List<QueryRequest.Reference> references,
        JwksJwtVerifier.JwtPrincipal authPrincipal,
        WorkspacePaths workspacePaths
) {

    public RuntimeRequestContext {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public record WorkspacePaths(
            String runtimeHome,
            String workingDirectory,
            String rootDir,
            String agentsDir,
            String chatsDir,
            String dataDir,
            String skillsDir,
            String schedulesDir,
            String ownerFile,
            String chatAttachmentsDir
    ) {
    }
}
