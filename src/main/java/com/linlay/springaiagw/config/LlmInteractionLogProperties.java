package com.linlay.springaiagw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm.interaction-log")
public class LlmInteractionLogProperties {

    private boolean enabled = true;
    private boolean maskSensitive = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    public void setMaskSensitive(boolean maskSensitive) {
        this.maskSensitive = maskSensitive;
    }
}
