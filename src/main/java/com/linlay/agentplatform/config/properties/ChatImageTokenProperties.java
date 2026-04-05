package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.chat-image-token")
public class ChatImageTokenProperties {

    private String secret;
    private long ttlSeconds = 86_400L;
    private boolean resourceTicketEnabled = true;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isResourceTicketEnabled() {
        return resourceTicketEnabled;
    }

    public void setResourceTicketEnabled(boolean resourceTicketEnabled) {
        this.resourceTicketEnabled = resourceTicketEnabled;
    }
}
