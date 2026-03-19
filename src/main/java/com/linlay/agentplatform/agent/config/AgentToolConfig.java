package com.linlay.agentplatform.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentToolConfig {
    private List<String> backends;
    private List<String> frontends;
    private List<String> actions;

    public List<String> getBackends() {
        return backends;
    }

    public void setBackends(List<String> backends) {
        this.backends = backends;
    }

    public List<String> getFrontends() {
        return frontends;
    }

    public void setFrontends(List<String> frontends) {
        this.frontends = frontends;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }
}
