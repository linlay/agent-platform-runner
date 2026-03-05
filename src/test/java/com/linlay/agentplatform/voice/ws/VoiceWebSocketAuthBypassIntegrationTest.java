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
                "agent.auth.enabled=true",
                "agent.voice.ws.enabled=true",
                "agent.voice.ws.auth-required=false",
                "memory.chats.dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-bypass-chats-${random.uuid}",
                "memory.chats.index.sqlite-file=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-bypass-chats-db-${random.uuid}/chats.db",
                "agent.viewports.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-bypass-viewports-${random.uuid}",
                "agent.tools.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-bypass-tools-${random.uuid}",
                "agent.skills.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-bypass-skills-${random.uuid}"
        }
)
@AutoConfigureWebTestClient
class VoiceWebSocketAuthBypassIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void wsPathShouldNotReturnUnauthorizedWhenVoiceWsAuthBypassed() {
        webTestClient.get()
                .uri("/api/ap/ws/voice")
                .exchange()
                .expectStatus()
                .value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
    }

    @Test
    void otherApiPathShouldStillRequireBearer() {
        webTestClient.get()
                .uri("/api/ap/agents")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
