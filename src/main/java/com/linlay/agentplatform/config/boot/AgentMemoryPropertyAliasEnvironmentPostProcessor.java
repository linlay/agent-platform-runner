package com.linlay.agentplatform.config.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentMemoryPropertyAliasEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, String> PROPERTY_ALIASES = Map.ofEntries(
            Map.entry("agent.memory.agent-memory.enabled", "agent.memory.enabled"),
            Map.entry("agent.memory.agent-memory.db-file-name", "agent.memory.db-file-name"),
            Map.entry("agent.memory.agent-memory.context-top-n", "agent.memory.context-top-n"),
            Map.entry("agent.memory.agent-memory.context-max-chars", "agent.memory.context-max-chars"),
            Map.entry("agent.memory.agent-memory.search-default-limit", "agent.memory.search-default-limit"),
            Map.entry("agent.memory.agent-memory.hybrid-vector-weight", "agent.memory.hybrid-vector-weight"),
            Map.entry("agent.memory.agent-memory.hybrid-fts-weight", "agent.memory.hybrid-fts-weight"),
            Map.entry("agent.memory.agent-memory.dual-write-markdown", "agent.memory.dual-write-markdown"),
            Map.entry("agent.memory.agent-memory.embedding-provider-key", "agent.memory.embedding-provider-key"),
            Map.entry("agent.memory.agent-memory.embedding-model", "agent.memory.embedding-model"),
            Map.entry("agent.memory.agent-memory.embedding-dimension", "agent.memory.embedding-dimension"),
            Map.entry("agent.memory.agent-memory.embedding-timeout-ms", "agent.memory.embedding-timeout-ms"),
            Map.entry("memory.storage.dir", "agent.memory.storage.dir"),
            Map.entry("memory.remember.enabled", "agent.memory.remember.enabled"),
            Map.entry("memory.remember.model-key", "agent.memory.remember.model-key"),
            Map.entry("memory.remember.timeout-ms", "agent.memory.remember.timeout-ms")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : PROPERTY_ALIASES.entrySet()) {
            String oldKey = entry.getKey();
            String newKey = entry.getValue();
            if (hasText(environment.getProperty(newKey))) {
                continue;
            }
            String oldValue = environment.getProperty(oldKey);
            if (!hasText(oldValue)) {
                continue;
            }
            aliases.put(newKey, oldValue);
        }
        if (aliases.isEmpty()) {
            return;
        }
        MutablePropertySources propertySources = environment.getPropertySources();
        MapPropertySource propertySource = new MapPropertySource("agentMemoryPropertyAliases", aliases);
        if (propertySources.contains("systemEnvironment")) {
            propertySources.addAfter("systemEnvironment", propertySource);
            return;
        }
        if (propertySources.contains("systemProperties")) {
            propertySources.addAfter("systemProperties", propertySource);
            return;
        }
        propertySources.addFirst(propertySource);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
