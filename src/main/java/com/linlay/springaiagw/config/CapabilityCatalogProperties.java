package com.linlay.springaiagw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.capability")
public class CapabilityCatalogProperties {

    private String toolsExternalDir = "tools";
    private String actionsExternalDir = "actions";
    private long refreshIntervalMs = 30_000L;

    public String getToolsExternalDir() {
        return toolsExternalDir;
    }

    public void setToolsExternalDir(String toolsExternalDir) {
        this.toolsExternalDir = toolsExternalDir;
    }

    public String getActionsExternalDir() {
        return actionsExternalDir;
    }

    public void setActionsExternalDir(String actionsExternalDir) {
        this.actionsExternalDir = actionsExternalDir;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }
}
