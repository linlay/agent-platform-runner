package com.linlay.agentplatform.voice.ws;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;

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
                "agent.auth.issuer=https://auth.example.local",
                "agent.voice.ws.enabled=true",
                "agent.voice.ws.auth-required=true",
                "memory.chats.dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-chats-${random.uuid}",
                "memory.chats.index.sqlite-file=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-chats-db-${random.uuid}/chats.db",
                "agent.viewports.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-viewports-${random.uuid}",
                "agent.tools.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-tools-${random.uuid}",
                "agent.skills.external-dir=${java.io.tmpdir}/springai-agent-platform-voice-ws-auth-skills-${random.uuid}"
        }
)
@AutoConfigureWebTestClient
class VoiceWebSocketAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private static RSAKey rsaKey;
    private static Path jwksFile;

    @BeforeAll
    static void beforeAll() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("voice-ws-auth-kid").generate();
        jwksFile = Files.createTempFile("voice-ws-jwks-", ".json");
        Files.writeString(jwksFile, new JWKSet(rsaKey.toPublicJWK()).toJSONObject().toString(), StandardCharsets.UTF_8);
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (jwksFile != null) {
            Files.deleteIfExists(jwksFile);
        }
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("agent.auth.jwks-uri", () -> jwksFile.toUri().toString());
        registry.add("agent.auth.jwks-cache-seconds", () -> 60);
    }

    @Test
    void wsPathShouldReturnUnauthorizedWhenQueryTokenMissing() {
        webTestClient.get()
                .uri("/api/ap/ws/voice")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void wsPathShouldNotReturnUnauthorizedWhenQueryTokenPresent() throws Exception {
        String token = issueToken("voice-user", "device-1");
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ap/ws/voice")
                        .queryParam("access_token", token)
                        .build())
                .exchange()
                .expectStatus()
                .value(status -> org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401));
    }

    @Test
    void wsPathShouldStillReturnUnauthorizedWhenOnlyAuthorizationHeaderProvided() throws Exception {
        String token = issueToken("voice-user-header-only", "device-header-only");
        webTestClient.get()
                .uri("/api/ap/ws/voice")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private String issueToken(String subject, String deviceId) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer("https://auth.example.local")
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "app")
                .claim("device_id", deviceId)
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claimsSet
        );
        JWSSigner signer = new RSASSASigner(rsaKey.toPrivateKey());
        jwt.sign(signer);
        return jwt.serialize();
    }
}
