package com.linlay.agentplatform.voice.ws;

import com.linlay.agentplatform.config.VoiceWsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "agent.voice.ws", name = "enabled", havingValue = "true")
public class VoiceWebSocketConfiguration {

    @Bean
    public HandlerMapping voiceWsHandlerMapping(VoiceWsProperties voiceWsProperties, VoiceWebSocketHandler voiceWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(-1);
        mapping.setUrlMap(Map.of(voiceWsProperties.normalizedPath(), voiceWebSocketHandler));
        return mapping;
    }

    @Bean
    @ConditionalOnMissingBean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
