package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.owner")
public class OwnerProperties {

    private String externalDir = "runtime/owner";

    public String getExternalDir() {
        return externalDir;
    }

    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }
}
