package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.tools.container-hub")
public class ContainerHubToolProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8080";
    private String authToken;
    private String defaultEnvironmentId;
    private int requestTimeoutMs = 30_000;
    private String defaultSandboxLevel = "run";
    private long agentIdleTimeoutMs = 600_000L;
    private long destroyQueueDelayMs = 5_000L;
    private MountConfig mounts = new MountConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getDefaultEnvironmentId() {
        return defaultEnvironmentId;
    }

    public void setDefaultEnvironmentId(String defaultEnvironmentId) {
        this.defaultEnvironmentId = defaultEnvironmentId;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String getDefaultSandboxLevel() {
        return defaultSandboxLevel;
    }

    public void setDefaultSandboxLevel(String defaultSandboxLevel) {
        this.defaultSandboxLevel = defaultSandboxLevel;
    }

    public long getAgentIdleTimeoutMs() {
        return agentIdleTimeoutMs;
    }

    public void setAgentIdleTimeoutMs(long agentIdleTimeoutMs) {
        this.agentIdleTimeoutMs = agentIdleTimeoutMs;
    }

    public long getDestroyQueueDelayMs() {
        return destroyQueueDelayMs;
    }

    public void setDestroyQueueDelayMs(long destroyQueueDelayMs) {
        this.destroyQueueDelayMs = destroyQueueDelayMs;
    }

    public MountConfig getMounts() {
        return mounts;
    }

    public void setMounts(MountConfig mounts) {
        this.mounts = mounts;
    }

    public static class MountConfig {
        private String dataDir;
        private String userDir = "./user";
        private String skillsDir;
        private String panDir = "./pan";

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public String getUserDir() {
            return userDir;
        }

        public void setUserDir(String userDir) {
            this.userDir = userDir;
        }

        public String getSkillsDir() {
            return skillsDir;
        }

        public void setSkillsDir(String skillsDir) {
            this.skillsDir = skillsDir;
        }

        public String getPanDir() {
            return panDir;
        }

        public void setPanDir(String panDir) {
            this.panDir = panDir;
        }
    }
}
