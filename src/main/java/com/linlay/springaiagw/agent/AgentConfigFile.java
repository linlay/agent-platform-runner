package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.linlay.springaiagw.model.ProviderType;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigFile {

    private String description;
    private ProviderType providerType;
    private String model;
    private String systemPrompt;
    private Boolean deepThink;
    private AgentMode mode;
    private String defaultCity;
    private String defaultBashCommand;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Boolean getDeepThink() {
        return deepThink;
    }

    public void setDeepThink(Boolean deepThink) {
        this.deepThink = deepThink;
    }

    public AgentMode getMode() {
        return mode;
    }

    public void setMode(AgentMode mode) {
        this.mode = mode;
    }

    public String getDefaultCity() {
        return defaultCity;
    }

    public void setDefaultCity(String defaultCity) {
        this.defaultCity = defaultCity;
    }

    public String getDefaultBashCommand() {
        return defaultBashCommand;
    }

    public void setDefaultBashCommand(String defaultBashCommand) {
        this.defaultBashCommand = defaultBashCommand;
    }
}
