package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.root")
public class RootProperties {

    private String externalDir = "root";

    public String getExternalDir() {
        return externalDir;
    }

    public void setExternalDir(String externalDir) {
        this.externalDir = externalDir;
    }
}
