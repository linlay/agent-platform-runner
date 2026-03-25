package com.linlay.agentplatform.agent.runtime;

import org.springframework.util.StringUtils;

import java.nio.file.Path;

public record MountDirectoryConfig(
        String chatsDir,
        String rootDir,
        String panDir,
        String skillsDir,
        String agentsDir,
        String toolsDir,
        String registriesDir,
        String viewportsDir,
        String teamsDir,
        String schedulesDir,
        String ownerDir
) {

    public String providersDir() {
        return resolveRegistryChild("providers");
    }

    public String modelsDir() {
        return resolveRegistryChild("models");
    }

    public String mcpServersDir() {
        return resolveRegistryChild("mcp-servers");
    }

    public String viewportServersDir() {
        return resolveRegistryChild("viewport-servers");
    }

    private String resolveRegistryChild(String child) {
        if (!StringUtils.hasText(registriesDir) || !StringUtils.hasText(child)) {
            return null;
        }
        return Path.of(registriesDir.trim(), child.trim()).normalize().toString();
    }
}
