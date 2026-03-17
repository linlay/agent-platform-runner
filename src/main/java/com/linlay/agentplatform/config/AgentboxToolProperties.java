package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools.agentbox")
public class AgentboxToolProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8080";
    private String authToken;
    private String defaultRuntime = "busybox";
    private String defaultVersion = "latest";
    private String defaultCwd = "/workspace";
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

    public String getDefaultRuntime() {
        return defaultRuntime;
    }

    public void setDefaultRuntime(String defaultRuntime) {
        this.defaultRuntime = defaultRuntime;
    }

    public String getDefaultVersion() {
        return defaultVersion;
    }

    public void setDefaultVersion(String defaultVersion) {
        this.defaultVersion = defaultVersion;
    }

    public String getDefaultCwd() {
        return defaultCwd;
    }

    public void setDefaultCwd(String defaultCwd) {
        this.defaultCwd = defaultCwd;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
