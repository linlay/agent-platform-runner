package com.linlay.agentplatform.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linlay.agentplatform.agent.AgentConfigFile;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentModelConfig {
    private String modelKey;
    private AgentConfigFile.ReasoningConfig reasoning;
    private Double temperature;
    @JsonProperty("top_p")
    private Double topP;
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public AgentConfigFile.ReasoningConfig getReasoning() {
        return reasoning;
    }

    public void setReasoning(AgentConfigFile.ReasoningConfig reasoning) {
        this.reasoning = reasoning;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}
