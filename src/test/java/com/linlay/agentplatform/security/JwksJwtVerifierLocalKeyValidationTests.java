package com.linlay.agentplatform.security;

import com.linlay.agentplatform.config.properties.AppAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
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
    void shouldIgnoreDeprecatedInlineLocalKeyWhenFileConfigured(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("local-public-key.pem"), toPem(generateRsaKey()), StandardCharsets.UTF_8);
        withUserDir(projectDir, () -> contextRunner
                .withPropertyValues(
                    "agent.auth.local-public-key=not-a-valid-pem",
                    "agent.auth.local-public-key-file=local-public-key.pem"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwksJwtVerifier.class)).isNotNull();
                }));
    }

    @Test
    void shouldLoadDefaultLocalKeyFromFallbackPemFile(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("local-public-key.pem"), toPem(generateRsaKey()), StandardCharsets.UTF_8);
        withUserDir(projectDir, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LocalKeyValidationTestConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off")
                    .run()) {
                assertThat(context.getBean(AppAuthProperties.class).getLocalPublicKeyFile()).isEqualTo("local-public-key.pem");
                assertThat(context.getBean(JwksJwtVerifier.class)).isNotNull();
            }
        });
    }

    @Test
    void shouldFailFastWhenLocalKeyFileIsMissing(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("project");
        Files.createDirectories(projectDir.resolve("configs"));
        withUserDir(projectDir, () -> contextRunner
                .withPropertyValues(
                    "agent.auth.local-public-key-file=local-public-key.pem"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("agent.auth.local-public-key-file does not exist");
                }));
    }

    @Test
    void shouldLoadLocalKeyFromPemFile(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("project");
        Path configsDir = projectDir.resolve("configs");
        Files.createDirectories(configsDir);
        Files.writeString(configsDir.resolve("local-public-key.pem"), toPem(generateRsaKey()), StandardCharsets.UTF_8);
        withUserDir(projectDir, () -> contextRunner
                .withPropertyValues(
                    "agent.auth.local-public-key-file=local-public-key.pem"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwksJwtVerifier.class)).isNotNull();
                }));
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

    private static void withUserDir(Path userDir, ThrowingRunnable action) throws Exception {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", userDir.toAbsolutePath().normalize().toString());
        try {
            action.run();
        } finally {
            restoreUserDir(previous);
        }
    }

    private static void restoreUserDir(String previous) {
        if (previous == null) {
            System.clearProperty("user.dir");
        } else {
            System.setProperty("user.dir", previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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
