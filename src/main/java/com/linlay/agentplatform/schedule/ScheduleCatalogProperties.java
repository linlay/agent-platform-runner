package com.linlay.agentplatform.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.schedule")
public class ScheduleCatalogProperties {

    private String externalDir = "schedules";
    private boolean enabled = true;
    private String defaultZoneId;
    private int poolSize = 4;

    public String getExternalDir() {
        return externalDir;
    }

    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultZoneId() {
        return defaultZoneId;
    }

    public void setDefaultZoneId(String defaultZoneId) {
        this.defaultZoneId = defaultZoneId;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
