package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigFile {

    private String description;
    private String providerKey;
    private String providerType;
    private String model;
    private String systemPrompt;
    private Boolean deepThink;
    private AgentMode mode;
    private List<String> tools;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
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

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }
}
