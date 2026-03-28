package com.linlay.agentplatform.stream.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.sse")
public record StreamSseProperties(
        Duration streamTimeout,
        Duration heartbeatInterval,
        boolean includeToolPayloadEvents
) {

    public StreamSseProperties {
        if (streamTimeout == null) {
            streamTimeout = Duration.ofMinutes(5);
        }
        if (heartbeatInterval == null) {
            heartbeatInterval = Duration.ofSeconds(15);
        }
    }
}
