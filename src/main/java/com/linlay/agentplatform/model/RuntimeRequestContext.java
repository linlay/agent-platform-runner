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
        WorkspacePaths workspacePaths,
        SandboxContext sandboxContext,
        List<AgentDigest> agentDigests
) {

    public RuntimeRequestContext {
        references = references == null ? List.of() : List.copyOf(references);
        agentDigests = agentDigests == null ? List.of() : List.copyOf(agentDigests);
    }

    public record SandboxContext(
            String environmentId,
            String configuredEnvironmentId,
            String defaultEnvironmentId,
            String level,
            boolean containerHubEnabled,
            boolean usesContainerHubTool,
            List<String> extraMounts,
            String environmentPrompt
    ) {

        public SandboxContext {
            extraMounts = extraMounts == null ? List.of() : List.copyOf(extraMounts);
        }
    }

    public record AgentDigest(
            String key,
            String name,
            String role,
            String description,
            String mode,
            String modelKey,
            List<String> tools,
            List<String> skills,
            SandboxDigest sandbox
    ) {

        public AgentDigest {
            tools = tools == null ? List.of() : List.copyOf(tools);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    public record SandboxDigest(
            String environmentId,
            String level
    ) {
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
