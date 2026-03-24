package com.linlay.agentplatform.config.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerProfileEnvironmentPostProcessorTest {

    private final DockerProfileEnvironmentPostProcessor processor = new DockerProfileEnvironmentPostProcessor();

    @Test
    void shouldFilterRuntimeDirectoryVariablesWhenDockerProfileIsActive() {
        StandardEnvironment environment = environmentWith(Map.of(
                "SPRING_PROFILES_ACTIVE", "docker",
                "AGENTS_DIR", "/host/agents",
                "SERVER_PORT", "19090",
                "SANDBOX_HOST_DIRS_FILE", "/tmp/runner-host.env",
                "AGENT_AUTH_ENABLED", "true"
        ));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("AGENTS_DIR")).isNull();
        assertThat(environment.getProperty("SERVER_PORT")).isNull();
        assertThat(environment.getProperty("SANDBOX_HOST_DIRS_FILE")).isEqualTo("/tmp/runner-host.env");
        assertThat(environment.getProperty("AGENT_AUTH_ENABLED")).isEqualTo("true");
    }

    @Test
    void shouldKeepRuntimeDirectoryVariablesOutsideDockerProfile() {
        StandardEnvironment environment = environmentWith(Map.of(
                "SPRING_PROFILES_ACTIVE", "local",
                "AGENTS_DIR", "/host/agents"
        ));

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty("AGENTS_DIR")).isEqualTo("/host/agents");
    }

    private StandardEnvironment environmentWith(Map<String, Object> systemEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new LinkedHashMap<>(systemEnvironment)
                )
        );
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of()));
        return environment;
    }
}
