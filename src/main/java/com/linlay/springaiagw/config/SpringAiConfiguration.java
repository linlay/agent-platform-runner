package com.linlay.springaiagw.config;

import io.netty.handler.logging.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StreamUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.Assert;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SpringAiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpringAiConfiguration.class);
    private static final String LLM_WIRETAP_LOGGER = "com.linlay.springaiagw.llm.wiretap";

    @Bean("bailianChatModel")
    public ChatModel bailianChatModel(AgentProviderProperties properties) {
        return buildChatModel("bailian", properties.getBailian());
    }

    @Bean("siliconflowChatModel")
    public ChatModel siliconflowChatModel(AgentProviderProperties properties) {
        return buildChatModel("siliconflow", properties.getSiliconflow());
    }

    @Bean("bailianChatClient")
    public ChatClient bailianChatClient(@Qualifier("bailianChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean("siliconflowChatClient")
    public ChatClient siliconflowChatClient(@Qualifier("siliconflowChatModel") ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    private ChatModel buildChatModel(String providerName, AgentProviderProperties.ProviderConfig providerConfig) {
        assertProviderConfig(providerName, providerConfig);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(providerConfig.getBaseUrl())
                .apiKey(providerConfig.getApiKey())
                .restClientBuilder(loggingRestClientBuilder())
                .webClientBuilder(loggingWebClientBuilder())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(providerConfig.getModel())
                .temperature(0.2)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private RestClient.Builder loggingRestClientBuilder() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    log.info("[llm-http][request] {} {}", request.getMethod(), request.getURI());
                    log.info("[llm-http][request-headers] {}", maskHeaders(request.getHeaders()));
                    log.info("[llm-http][request-body]\n{}", new String(body, StandardCharsets.UTF_8));

                    ClientHttpResponse response = execution.execute(request, body);
                    byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());

                    log.info("[llm-http][response] status={}", response.getStatusCode().value());
                    log.info("[llm-http][response-headers] {}", response.getHeaders());
                    log.info("[llm-http][response-body]\n{}", new String(responseBody, StandardCharsets.UTF_8));

                    return new ReplayableClientHttpResponse(response, responseBody);
                });
    }

    private WebClient.Builder loggingWebClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .wiretap(LLM_WIRETAP_LOGGER, LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build());
    }

    private HttpHeaders maskHeaders(HttpHeaders headers) {
        HttpHeaders masked = new HttpHeaders();
        masked.putAll(headers);
        List<String> authValues = masked.get(HttpHeaders.AUTHORIZATION);
        if (authValues != null && !authValues.isEmpty()) {
            masked.set(HttpHeaders.AUTHORIZATION, "Bearer ***");
        }
        return masked;
    }

    private void assertProviderConfig(String providerName, AgentProviderProperties.ProviderConfig providerConfig) {
        Assert.notNull(providerConfig, "Missing config: agent.providers." + providerName);
        Assert.hasText(providerConfig.getBaseUrl(),
                "Missing config: agent.providers." + providerName + ".base-url");
        Assert.hasText(providerConfig.getApiKey(),
                "Missing config: agent.providers." + providerName + ".api-key");
        Assert.hasText(providerConfig.getModel(),
                "Missing config: agent.providers." + providerName + ".model");
    }

    private static final class ReplayableClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        private ReplayableClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
