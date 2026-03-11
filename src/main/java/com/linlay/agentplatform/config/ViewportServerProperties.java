package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.viewport-servers")
public class ViewportServerProperties {

    private boolean enabled = true;
    private String protocolVersion = "2025-06";
    private int connectTimeoutMs = 3_000;
    private int retry = 1;
    private long reconnectIntervalMs = 60_000;
    private Registry registry = new Registry();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRetry() {
        return retry;
    }

    public void setRetry(int retry) {
        this.retry = retry;
    }

    public long getReconnectIntervalMs() {
        return reconnectIntervalMs;
    }

    public void setReconnectIntervalMs(long reconnectIntervalMs) {
        this.reconnectIntervalMs = reconnectIntervalMs;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry == null ? new Registry() : registry;
    }

    public static class Registry {
        private String externalDir = "viewport-servers";

        public String getExternalDir() {
            return externalDir;
        }

        public void setExternalDir(String externalDir) {
            this.externalDir = externalDir;
        }
    }
}
