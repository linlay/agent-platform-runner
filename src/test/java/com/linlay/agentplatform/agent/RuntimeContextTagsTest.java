package com.linlay.agentplatform.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextTagsTest {

    @Test
    void shouldRecognizeMemoryTag() {
        assertThat(RuntimeContextTags.isSupported("memory")).isTrue();
        assertThat(RuntimeContextTags.normalize(java.util.List.of("MEMORY", "context", "memory")))
                .containsExactly("memory", "context");
    }
}
