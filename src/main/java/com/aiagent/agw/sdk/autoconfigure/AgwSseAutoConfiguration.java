package com.aiagent.agw.sdk.autoconfigure;

import com.aiagent.agw.sdk.service.AgwEventAssembler;
import com.aiagent.agw.sdk.service.AgwSseStreamer;
import com.aiagent.agw.sdk.service.SseFlushWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

@AutoConfiguration
@ConditionalOnClass({Flux.class, ServerSentEvent.class, ObjectMapper.class})
@EnableConfigurationProperties(AgwSseProperties.class)
public class AgwSseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper agwObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgwEventAssembler agwEventAssembler() {
        return new AgwEventAssembler();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgwSseStreamer agwSseStreamer(AgwEventAssembler eventAssembler, ObjectMapper objectMapper, AgwSseProperties properties) {
        return new AgwSseStreamer(eventAssembler, objectMapper, properties.streamTimeout(), properties.heartbeatInterval());
    }

    @Bean
    @ConditionalOnMissingBean
    public SseFlushWriter sseFlushWriter() {
        return new SseFlushWriter();
    }
}
