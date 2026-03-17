package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools.container-hub")
public class ContainerHubToolProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8080";
    private String authToken;
    private String defaultEnvironmentId;
    private int requestTimeoutMs = 30_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getDefaultEnvironmentId() {
        return defaultEnvironmentId;
    }

    public void setDefaultEnvironmentId(String defaultEnvironmentId) {
        this.defaultEnvironmentId = defaultEnvironmentId;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
