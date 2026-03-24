package com.linlay.agentplatform.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DockerProfileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final Set<String> FILTERED_ENV_KEYS = Set.of(
            "SERVER_PORT",
            "AGENTS_DIR",
            "OWNER_DIR",
            "TEAMS_DIR",
            "MODELS_DIR",
            "PROVIDERS_DIR",
            "MCP_SERVERS_DIR",
            "VIEWPORT_SERVERS_DIR",
            "SKILLS_MARKET_DIR",
            "SCHEDULES_DIR",
            "CHATS_DIR",
            "ROOT_DIR",
            "PAN_DIR"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isDockerProfileActive(environment)) {
            return;
        }
        MutablePropertySources propertySources = environment.getPropertySources();
        PropertySource<?> propertySource = propertySources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource)) {
            return;
        }

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String propertyName : enumerablePropertySource.getPropertyNames()) {
            if (FILTERED_ENV_KEYS.contains(propertyName)) {
                continue;
            }
            Object value = enumerablePropertySource.getProperty(propertyName);
            if (value != null) {
                filtered.put(propertyName, value);
            }
        }

        propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, filtered)
        );
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean isDockerProfileActive(ConfigurableEnvironment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (isDockerProfile(profile)) {
                return true;
            }
        }
        String configuredProfiles = environment.getProperty("spring.profiles.active");
        if (!StringUtils.hasText(configuredProfiles)) {
            return false;
        }
        return List.of(configuredProfiles.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .anyMatch(this::isDockerProfile);
    }

    private boolean isDockerProfile(String profile) {
        return "docker".equals(profile == null ? null : profile.trim().toLowerCase(Locale.ROOT));
    }
}
