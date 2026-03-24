package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools")
public class ToolProperties {

    private String externalDir;
    private long refreshIntervalMs = 30_000L;

    public String getExternalDir() {
        return externalDir;
    }

    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }
}
