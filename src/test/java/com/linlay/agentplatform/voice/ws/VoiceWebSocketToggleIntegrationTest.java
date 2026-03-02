package com.linlay.agentplatform.voice.ws;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "agent.providers.bailian.base-url=https://example.com/v1",
                "agent.providers.bailian.api-key=test-bailian-key",
                "agent.providers.bailian.model=test-bailian-model",
                "agent.providers.siliconflow.base-url=https://example.com/v1",
                "agent.providers.siliconflow.api-key=test-siliconflow-key",
                "agent.providers.siliconflow.model=test-siliconflow-model",
                "agent.auth.enabled=false",
                "agent.voice.ws.enabled=false",
                "memory.chat.dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-toggle-chats-${random.uuid}",
                "memory.chat.index.sqlite-file=${java.io.tmpdir}/springai-agent-platform-voice-ws-toggle-chats-db-${random.uuid}/chats.db",
                "agent.viewport.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-toggle-viewports-${random.uuid}",
                "agent.capability.tools-external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-toggle-tools-${random.uuid}",
                "agent.skill.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-toggle-skills-${random.uuid}"
        }
)
@AutoConfigureWebTestClient
class VoiceWebSocketToggleIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void wsPathShouldBeUnavailableWhenFeatureDisabled() {
        webTestClient.get()
                .uri("/api/ap/ws/voice")
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
