package com.linlay.agentplatform.security;

import com.linlay.agentplatform.config.AppAuthProperties;
import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwksJwtVerifierLocalKeyValidationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LocalKeyValidationTestConfiguration.class);

    @Test
    void shouldTreatBlankLocalKeyAsUnconfigured() {
        contextRunner
            .withPropertyValues(
                "agent.auth.local-public-key="
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JwksJwtVerifier.class)).isNotNull();
            });
    }

    @Test
    void shouldFailFastWhenLocalKeyPemIsInvalid() {
        contextRunner
            .withPropertyValues(
                "agent.auth.local-public-key=not-a-valid-pem"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("agent.auth.local-public-key is not a valid PEM RSA public key");
            });
    }

    @Test
    void shouldFailFastWhenLocalKeyAndFileAreConfiguredTogether() {
        contextRunner
            .withPropertyValues(
                "agent.auth.local-public-key=not-a-valid-pem",
                "agent.auth.local-public-key-file=auth/local-public-key.pem"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("agent.auth.local-public-key and agent.auth.local-public-key-file cannot be configured together");
            });
    }

    @Test
    void shouldFailFastWhenLocalKeyFileIsMissing(@TempDir Path tempDir) {
        String previous = System.getProperty(ConfigDirectorySupport.CONFIG_DIR_ENV);
        System.setProperty(ConfigDirectorySupport.CONFIG_DIR_ENV, tempDir.toString());
        try {
            contextRunner
                .withPropertyValues(
                    "agent.auth.local-public-key-file=auth/local-public-key.pem"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("agent.auth.local-public-key-file does not exist");
                });
        } finally {
            restoreConfigDir(previous);
        }
    }

    @Test
    void shouldLoadLocalKeyFromPemFile(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty(ConfigDirectorySupport.CONFIG_DIR_ENV);
        Path authDir = tempDir.resolve("auth");
        Files.createDirectories(authDir);
        Files.writeString(authDir.resolve("local-public-key.pem"), toPem(generateRsaKey()), StandardCharsets.UTF_8);
        System.setProperty(ConfigDirectorySupport.CONFIG_DIR_ENV, tempDir.toString());
        try {
            contextRunner
                .withPropertyValues(
                    "agent.auth.local-public-key-file=auth/local-public-key.pem"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwksJwtVerifier.class)).isNotNull();
                });
        } finally {
            restoreConfigDir(previous);
        }
    }

    @Test
    void shouldFailFastWhenJwksConfiguredPartially() {
        contextRunner
            .withPropertyValues(
                "agent.auth.jwks-uri=https://auth.example.local/api/auth/jwks",
                "agent.auth.jwks-cache-seconds=60"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                        "agent.auth.jwks-uri, agent.auth.issuer and agent.auth.jwks-cache-seconds must be configured together"
                    );
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppAuthProperties.class)
    static class LocalKeyValidationTestConfiguration {

        @Bean
        JwksJwtVerifier jwksJwtVerifier(AppAuthProperties authProperties) {
            return new JwksJwtVerifier(authProperties);
        }
    }

    private static void restoreConfigDir(String previous) {
        if (previous == null) {
            System.clearProperty(ConfigDirectorySupport.CONFIG_DIR_ENV);
        } else {
            System.setProperty(ConfigDirectorySupport.CONFIG_DIR_ENV, previous);
        }
    }

    private static RSAKey generateRsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("local-key-test").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to generate RSA key for test", ex);
        }
    }

    private static String toPem(RSAKey rsaKey) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(rsaKey.toRSAPublicKey().getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to encode RSA public key as PEM", ex);
        }
    }
}
