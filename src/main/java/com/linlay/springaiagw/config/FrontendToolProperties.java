package com.linlay.springaiagw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools.frontend")
public class FrontendToolProperties {

    private long submitTimeoutMs = 300_000L;

    public long getSubmitTimeoutMs() {
        return submitTimeoutMs;
    }

    public void setSubmitTimeoutMs(long submitTimeoutMs) {
        this.submitTimeoutMs = submitTimeoutMs;
    }
}
