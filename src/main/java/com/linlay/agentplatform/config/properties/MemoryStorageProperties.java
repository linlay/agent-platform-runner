package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memory.storage")
public class MemoryStorageProperties {

    private String dir = "runtime/memory";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
