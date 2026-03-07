package com.linlay.agentplatform.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class LegacyConfigKeyGuard {
    // TODO(remove-after: 2026-06-30): Drop fail-fast guard after legacy key migration window closes.

    private static final Map<String, String> LEGACY_PROPERTY_PREFIX_MAPPINGS = Map.ofEntries(
            Map.entry("agent.catalog.", "agent.agents."),
            Map.entry("agent.viewport.", "agent.viewports."),
            Map.entry("agent.capability.", "agent.tools."),
            Map.entry("agent.skill.", "agent.skills."),
            Map.entry("agent.team.", "agent.teams."),
            Map.entry("agent.model.", "agent.models."),
            Map.entry("agent.mcp.", "agent.mcp-servers."),
            Map.entry("memory.chat.", "memory.chats.")
    );

    private static final Map<String, String> LEGACY_ENV_MAPPINGS = legacyEnvMappings();

    private final ConfigurableEnvironment environment;
    private final Supplier<Map<String, String>> envSupplier;

    @Autowired
    public LegacyConfigKeyGuard(ConfigurableEnvironment environment) {
        this(environment, System::getenv);
    }

    LegacyConfigKeyGuard(ConfigurableEnvironment environment, Supplier<Map<String, String>> envSupplier) {
        this.environment = environment;
        this.envSupplier = envSupplier;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        Set<String> legacyPropertyKeys = detectLegacyPropertyKeys();
        Set<String> legacyEnvVars = detectLegacyEnvironmentVariables();
        if (legacyPropertyKeys.isEmpty() && legacyEnvVars.isEmpty()) {
            return;
        }
        throw buildException(legacyPropertyKeys, legacyEnvVars);
    }

    Set<String> detectLegacyPropertyKeys() {
        Set<String> detected = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (shouldSkipPropertySource(propertySource)) {
                continue;
            }
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource)) {
                continue;
            }
            for (String propertyName : enumerablePropertySource.getPropertyNames()) {
                if (!StringUtils.hasText(propertyName)) {
                    continue;
                }
                if (isLegacyPropertyKey(propertyName)) {
                    detected.add(propertyName);
                }
            }
        }
        return detected;
    }

    private boolean shouldSkipPropertySource(PropertySource<?> propertySource) {
        String name = propertySource.getName();
        if (!StringUtils.hasText(name)) {
            return false;
        }
        return "configurationProperties".equals(name) || name.contains("systemEnvironment");
    }

    Set<String> detectLegacyEnvironmentVariables() {
        Map<String, String> env = envSupplier.get();
        if (env == null || env.isEmpty()) {
            return Set.of();
        }
        Set<String> detected = new LinkedHashSet<>();
        for (String legacyEnv : LEGACY_ENV_MAPPINGS.keySet()) {
            if (StringUtils.hasText(env.get(legacyEnv))) {
                detected.add(legacyEnv);
            }
        }
        return detected;
    }

    private boolean isLegacyPropertyKey(String propertyName) {
        for (String legacyPrefix : LEGACY_PROPERTY_PREFIX_MAPPINGS.keySet()) {
            if (propertyName.startsWith(legacyPrefix)) {
                return true;
            }
        }
        return false;
    }

    private IllegalStateException buildException(Set<String> legacyPropertyKeys, Set<String> legacyEnvVars) {
        StringBuilder message = new StringBuilder();
        message.append("Legacy configuration keys/env vars are not supported. ");
        message.append("Use the new naming scheme only.");
        if (!legacyPropertyKeys.isEmpty()) {
            message.append("\nDetected legacy property keys: ")
                    .append(String.join(", ", legacyPropertyKeys));
        }
        if (!legacyEnvVars.isEmpty()) {
            message.append("\nDetected legacy environment variables: ")
                    .append(String.join(", ", legacyEnvVars));
        }
        message.append("\nLegacy property prefix mappings: ")
                .append(formatMappings(LEGACY_PROPERTY_PREFIX_MAPPINGS));
        message.append("\nLegacy environment variable mappings: ")
                .append(formatMappings(LEGACY_ENV_MAPPINGS));
        return new IllegalStateException(message.toString());
    }

    private static String formatMappings(Map<String, String> mappings) {
        return mappings.entrySet().stream()
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static Map<String, String> legacyEnvMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("AGENT_EXTERNAL_DIR", "AGENT_AGENTS_EXTERNAL_DIR");
        mappings.put("AGENT_REFRESH_INTERVAL_MS", "AGENT_AGENTS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_VIEWPORT_EXTERNAL_DIR", "AGENT_VIEWPORTS_EXTERNAL_DIR");
        mappings.put("AGENT_VIEWPORT_REFRESH_INTERVAL_MS", "AGENT_VIEWPORTS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_CAPABILITY_REFRESH_INTERVAL_MS", "AGENT_TOOLS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_SKILL_EXTERNAL_DIR", "AGENT_SKILLS_EXTERNAL_DIR");
        mappings.put("AGENT_SKILL_REFRESH_INTERVAL_MS", "AGENT_SKILLS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_SKILL_MAX_PROMPT_CHARS", "AGENT_SKILLS_MAX_PROMPT_CHARS");
        mappings.put("AGENT_TEAM_EXTERNAL_DIR", "AGENT_TEAMS_EXTERNAL_DIR");
        mappings.put("AGENT_TEAM_REFRESH_INTERVAL_MS", "AGENT_TEAMS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_MODEL_EXTERNAL_DIR", "AGENT_MODELS_EXTERNAL_DIR");
        mappings.put("AGENT_MODEL_REFRESH_INTERVAL_MS", "AGENT_MODELS_REFRESH_INTERVAL_MS");
        mappings.put("AGENT_MCP_ENABLED", "AGENT_MCP_SERVERS_ENABLED");
        mappings.put("AGENT_MCP_PROTOCOL_VERSION", "AGENT_MCP_SERVERS_PROTOCOL_VERSION");
        mappings.put("AGENT_MCP_CONNECT_TIMEOUT_MS", "AGENT_MCP_SERVERS_CONNECT_TIMEOUT_MS");
        mappings.put("AGENT_MCP_RETRY", "AGENT_MCP_SERVERS_RETRY");
        mappings.put("AGENT_MCP_REGISTRY_EXTERNAL_DIR", "AGENT_MCP_SERVERS_REGISTRY_EXTERNAL_DIR");
        mappings.put("MEMORY_CHAT_DIR", "MEMORY_CHATS_DIR");
        mappings.put("MEMORY_CHAT_K", "MEMORY_CHATS_K");
        mappings.put("MEMORY_CHAT_CHARSET", "MEMORY_CHATS_CHARSET");
        mappings.put("MEMORY_CHAT_ACTION_TOOLS", "MEMORY_CHATS_ACTION_TOOLS");
        mappings.put("MEMORY_CHAT_INDEX_SQLITE_FILE", "MEMORY_CHATS_INDEX_SQLITE_FILE");
        mappings.put("MEMORY_CHAT_INDEX_AUTO_REBUILD_ON_INCOMPATIBLE_SCHEMA", "MEMORY_CHATS_INDEX_AUTO_REBUILD_ON_INCOMPATIBLE_SCHEMA");
        return Map.copyOf(mappings);
    }
}
