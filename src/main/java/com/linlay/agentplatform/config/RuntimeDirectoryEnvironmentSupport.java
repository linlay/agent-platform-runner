package com.linlay.agentplatform.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeDirectoryEnvironmentSupport {

    private static final Map<String, String> DEPRECATED_DIRECTORY_VARIABLES = deprecatedDirectoryVariables();

    private RuntimeDirectoryEnvironmentSupport() {
    }

    public static void validateNoDeprecatedDirectoryVariables(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }
        for (Map.Entry<String, String> entry : DEPRECATED_DIRECTORY_VARIABLES.entrySet()) {
            String configured = environment.getProperty(entry.getKey());
            if (StringUtils.hasText(configured)) {
                throw new IllegalStateException("Deprecated directory environment variable '" + entry.getKey()
                        + "' is no longer supported. Use '" + entry.getValue() + "' instead.");
            }
        }
    }

    private static Map<String, String> deprecatedDirectoryVariables() {
        LinkedHashMap<String, String> vars = new LinkedHashMap<>();
        vars.put("AGENT_CONFIG_DIR", "CONFIGS_DIR");
        vars.put("AGENT_AGENTS_EXTERNAL_DIR", "AGENTS_DIR");
        vars.put("AGENT_TEAMS_EXTERNAL_DIR", "TEAMS_DIR");
        vars.put("AGENT_MODELS_EXTERNAL_DIR", "MODELS_DIR");
        vars.put("AGENT_PROVIDERS_EXTERNAL_DIR", "PROVIDERS_DIR");
        vars.put("AGENT_TOOLS_EXTERNAL_DIR", "TOOLS_DIR");
        vars.put("AGENT_SKILLS_EXTERNAL_DIR", "SKILLS_DIR");
        vars.put("AGENT_VIEWPORTS_EXTERNAL_DIR", "VIEWPORTS_DIR");
        vars.put("AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR", "MCP_SERVERS_DIR");
        vars.put("AGENT_VIEWPORT_SERVERS_REGISTRY_EXTERNAL_DIR", "VIEWPORT_SERVERS_DIR");
        vars.put("AGENT_SCHEDULE_EXTERNAL_DIR", "SCHEDULES_DIR");
        vars.put("AGENT_DATA_EXTERNAL_DIR", "DATA_DIR");
        vars.put("MEMORY_CHATS_DIR", "CHATS_DIR");
        return Map.copyOf(vars);
    }
}
