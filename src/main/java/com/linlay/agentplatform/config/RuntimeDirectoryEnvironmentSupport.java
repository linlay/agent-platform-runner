package com.linlay.agentplatform.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeDirectoryEnvironmentSupport {

    private static final Map<String, String> UNSUPPORTED_DIRECTORY_VARIABLES = unsupportedDirectoryVariables();
    private static final Map<String, String> DEPRECATED_DIRECTORY_VARIABLES = deprecatedDirectoryVariables();
    private static final Map<String, String> DEPRECATED_PROPERTIES = deprecatedProperties();

    private RuntimeDirectoryEnvironmentSupport() {
    }

    public static void validateNoUnsupportedDirectoryVariables(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }
        for (Map.Entry<String, String> entry : UNSUPPORTED_DIRECTORY_VARIABLES.entrySet()) {
            String configured = environment.getProperty(entry.getKey());
            if (StringUtils.hasText(configured)) {
                throw new IllegalStateException("Environment variable '" + entry.getKey()
                        + "' is no longer supported. " + entry.getValue());
            }
        }
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

    public static void validateNoDeprecatedProperties(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }
        for (Map.Entry<String, String> entry : DEPRECATED_PROPERTIES.entrySet()) {
            String configured = environment.getProperty(entry.getKey());
            if (StringUtils.hasText(configured)) {
                throw new IllegalStateException("Deprecated property '" + entry.getKey()
                        + "' is no longer supported. Use '" + entry.getValue() + "' or the new directory layout instead.");
            }
        }
    }

    private static Map<String, String> unsupportedDirectoryVariables() {
        LinkedHashMap<String, String> vars = new LinkedHashMap<>();
        vars.put(ConfigDirectorySupport.CONFIG_DIR_ENV,
                "The runner config directory is fixed to './configs' (or '/opt/configs' in container) and must not be overridden.");
        vars.put("AGENT_CONFIG_DIR",
                "The runner config directory is fixed to './configs' (or '/opt/configs' in container) and must not be overridden.");
        return Map.copyOf(vars);
    }

    private static Map<String, String> deprecatedDirectoryVariables() {
        LinkedHashMap<String, String> vars = new LinkedHashMap<>();
        vars.put("AGENT_AGENTS_EXTERNAL_DIR", "AGENTS_DIR");
        vars.put("AGENT_TEAMS_EXTERNAL_DIR", "TEAMS_DIR");
        vars.put("AGENT_MODELS_EXTERNAL_DIR", "MODELS_DIR");
        vars.put("AGENT_PROVIDERS_EXTERNAL_DIR", "PROVIDERS_DIR");
        vars.put("AGENT_TOOLS_EXTERNAL_DIR", "classpath:/tools");
        vars.put("AGENT_SKILLS_EXTERNAL_DIR", "SKILLS_MARKET_DIR");
        vars.put("AGENT_VIEWPORTS_EXTERNAL_DIR", "classpath:/viewports");
        vars.put("AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR", "MCP_SERVERS_DIR");
        vars.put("AGENT_VIEWPORT_SERVERS_REGISTRY_EXTERNAL_DIR", "VIEWPORT_SERVERS_DIR");
        vars.put("AGENT_SCHEDULE_EXTERNAL_DIR", "SCHEDULES_DIR");
        vars.put("AGENT_DATA_EXTERNAL_DIR", "CHATS_DIR");
        vars.put("MEMORY_CHATS_DIR", "CHATS_DIR");
        vars.put("SKILLS_DIR", "SKILLS_MARKET_DIR");
        vars.put("TOOLS_DIR", "classpath:/tools");
        vars.put("VIEWPORTS_DIR", "classpath:/viewports");
        vars.put("DATA_DIR", "CHATS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_DATA_DIR", "CHATS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_USER_DIR", "WORKSPACE_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_SKILLS_DIR", "SKILLS_MARKET_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_PAN_DIR", "PAN_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_TOOLS_DIR", "classpath:/tools");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_AGENTS_DIR", "AGENTS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_MODELS_DIR", "MODELS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_VIEWPORTS_DIR", "classpath:/viewports");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_TEAMS_DIR", "TEAMS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_SCHEDULES_DIR", "SCHEDULES_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_MCP_SERVERS_DIR", "MCP_SERVERS_DIR");
        vars.put("AGENT_CONTAINER_HUB_MOUNTS_PROVIDERS_DIR", "PROVIDERS_DIR");
        return Map.copyOf(vars);
    }

    private static Map<String, String> deprecatedProperties() {
        LinkedHashMap<String, String> vars = new LinkedHashMap<>();
        vars.put("agent.tools.external-dir", "classpath tools");
        vars.put("agent.tools.refresh-interval-ms", "removed");
        vars.put("agent.viewports.external-dir", "classpath viewports");
        vars.put("agent.viewports.refresh-interval-ms", "removed");
        vars.put("agent.data.external-dir", "memory.chats.dir");
        vars.put("agent.tools.container-hub.mounts.data-dir", "memory.chats.dir");
        vars.put("agent.tools.container-hub.mounts.user-dir", "agent.workspace.external-dir");
        vars.put("agent.tools.container-hub.mounts.skills-dir", "agent.skills.external-dir");
        vars.put("agent.tools.container-hub.mounts.pan-dir", "agent.pan.external-dir");
        vars.put("agent.tools.container-hub.mounts.tools-dir", "classpath tools");
        vars.put("agent.tools.container-hub.mounts.agents-dir", "agent.agents.external-dir");
        vars.put("agent.tools.container-hub.mounts.models-dir", "agent.models.external-dir");
        vars.put("agent.tools.container-hub.mounts.viewports-dir", "classpath viewports");
        vars.put("agent.tools.container-hub.mounts.teams-dir", "agent.teams.external-dir");
        vars.put("agent.tools.container-hub.mounts.schedules-dir", "agent.schedule.external-dir");
        vars.put("agent.tools.container-hub.mounts.mcp-servers-dir", "agent.mcp-servers.registry.external-dir");
        vars.put("agent.tools.container-hub.mounts.providers-dir", "agent.providers.external-dir");
        return Map.copyOf(vars);
    }
}
