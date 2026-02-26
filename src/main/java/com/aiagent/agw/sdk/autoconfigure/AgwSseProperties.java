package com.aiagent.agw.sdk.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agw.sse")
public record AgwSseProperties(
        Duration streamTimeout,
        Duration heartbeatInterval
) {

    public AgwSseProperties {
        if (streamTimeout == null) {
            streamTimeout = Duration.ofMinutes(5);
        }
        if (heartbeatInterval == null) {
            heartbeatInterval = Duration.ofSeconds(15);
        }
    }

    public AgwSseProperties() {
        this(null, null);
    }
}
