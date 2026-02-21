package com.linlay.agentplatform.security;

import com.linlay.agentplatform.config.AppAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class JwksJwtVerifierLocalKeyValidationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LocalKeyValidationTestConfiguration.class);

    @Test
    void shouldFailFastWhenLocalKeyIsBlank() {
        contextRunner
            .withPropertyValues(
                "agent.auth.local-public-key="
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("agent.auth.local-public-key cannot be blank");
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
}
