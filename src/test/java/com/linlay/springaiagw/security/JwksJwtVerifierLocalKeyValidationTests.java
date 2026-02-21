package com.linlay.springaiagw.security;

import com.linlay.springaiagw.config.AppAuthProperties;
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
    void shouldFailFastWhenLocalKeyEnabledButEmpty() {
        contextRunner
            .withPropertyValues(
                "agw.auth.local-public-key-enabled=true",
                "agw.auth.local-public-key="
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("agw.auth.local-public-key is required");
            });
    }

    @Test
    void shouldFailFastWhenLocalKeyEnabledButPemIsInvalid() {
        contextRunner
            .withPropertyValues(
                "agw.auth.local-public-key-enabled=true",
                "agw.auth.local-public-key=not-a-valid-pem"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasCauseInstanceOf(IllegalStateException.class);
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("agw.auth.local-public-key is not a valid PEM RSA public key");
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
