package com.linlay.agentplatform.config;

import io.netty.handler.logging.LogLevel;
import com.linlay.agentplatform.service.llm.LlmLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.time.Duration;
import java.util.function.Consumer;

@Configuration
public class LlmConnectionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectionConfiguration.class);
    private static final String LLM_WIRETAP_LOGGER = "com.linlay.agentplatform.llm.wiretap";

    @Bean
    public ConnectionProvider llmConnectionProvider() {
        return ConnectionProvider.builder("llm-pool")
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public WebClient.Builder loggingWebClientBuilder(
            LlmInteractionLogProperties logProperties,
            ConnectionProvider llmConnectionProvider) {
        HttpClient httpClient = HttpClient.create(llmConnectionProvider);
        if (logProperties.isEnabled() && !logProperties.isMaskSensitive()) {
            httpClient = httpClient.wiretap(LLM_WIRETAP_LOGGER, LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL);
        }

        WebClient.Builder builder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build());
        if (!logProperties.isEnabled()) {
            return builder;
        }

        boolean maskSensitive = logProperties.isMaskSensitive();
        return builder.filter((request, next) -> {
            log.info("[llm-webclient][request] {} {}", request.method(), request.url());
            log.info("[llm-webclient][request-headers] {}", LlmLogSanitizer.maskHeaders(request.headers(), maskSensitive));
            return next.exchange(request)
                    .doOnNext(logResponse(maskSensitive, request));
        });
    }

    private Consumer<ClientResponse> logResponse(boolean maskSensitive, ClientRequest request) {
        return response -> {
            log.info(
                    "[llm-webclient][response] {} {} status={}",
                    request.method(),
                    request.url(),
                    response.statusCode().value()
            );
            log.info("[llm-webclient][response-headers] {}", LlmLogSanitizer.maskHeaders(response.headers().asHttpHeaders(), maskSensitive));
        };
    }
}
