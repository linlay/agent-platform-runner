package com.linlay.agentplatform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyConfigKeyGuardTest {

    @Test
    void shouldFailWhenLegacyPropertyKeyDetected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("agent.catalog.external-dir", "/tmp/agents");
        LegacyConfigKeyGuard guard = new LegacyConfigKeyGuard(environment, Map::of);

        assertThatThrownBy(guard::validateLegacyConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.catalog.external-dir")
                .hasMessageContaining("agent.catalog. -> agent.agents.");
    }

    @Test
    void shouldFailWhenLegacyEnvVarDetected() {
        MockEnvironment environment = new MockEnvironment();
        LegacyConfigKeyGuard guard = new LegacyConfigKeyGuard(environment, () -> Map.of(
                "AGENT_EXTERNAL_DIR", "/tmp/agents"
        ));

        assertThatThrownBy(guard::validateLegacyConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_EXTERNAL_DIR")
                .hasMessageContaining("AGENT_EXTERNAL_DIR -> AGENT_AGENTS_EXTERNAL_DIR");
    }

    @Test
    void shouldPassWhenOnlyNewConfigKeysAreUsed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("agent.agents.external-dir", "/tmp/agents");
        environment.setProperty("agent.tools.external-dir", "/tmp/tools");
        environment.setProperty("memory.chats.dir", "/tmp/chats");
        LegacyConfigKeyGuard guard = new LegacyConfigKeyGuard(environment, () -> Map.of(
                "AGENT_AGENTS_EXTERNAL_DIR", "/tmp/agents",
                "MEMORY_CHATS_DIR", "/tmp/chats"
        ));

        assertThatCode(guard::validateLegacyConfiguration).doesNotThrowAnyException();
    }
}
