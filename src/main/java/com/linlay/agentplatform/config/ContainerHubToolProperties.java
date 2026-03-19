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
        private String toolsDir;
        private String agentsDir;
        private String modelsDir;
        private String viewportsDir;
        private String teamsDir;
        private String schedulesDir;
        private String mcpServersDir;
        private String providersDir;

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

        public String getToolsDir() {
            return toolsDir;
        }

        public void setToolsDir(String toolsDir) {
            this.toolsDir = toolsDir;
        }

        public String getAgentsDir() {
            return agentsDir;
        }

        public void setAgentsDir(String agentsDir) {
            this.agentsDir = agentsDir;
        }

        public String getModelsDir() {
            return modelsDir;
        }

        public void setModelsDir(String modelsDir) {
            this.modelsDir = modelsDir;
        }

        public String getViewportsDir() {
            return viewportsDir;
        }

        public void setViewportsDir(String viewportsDir) {
            this.viewportsDir = viewportsDir;
        }

        public String getTeamsDir() {
            return teamsDir;
        }

        public void setTeamsDir(String teamsDir) {
            this.teamsDir = teamsDir;
        }

        public String getSchedulesDir() {
            return schedulesDir;
        }

        public void setSchedulesDir(String schedulesDir) {
            this.schedulesDir = schedulesDir;
        }

        public String getMcpServersDir() {
            return mcpServersDir;
        }

        public void setMcpServersDir(String mcpServersDir) {
            this.mcpServersDir = mcpServersDir;
        }

        public String getProvidersDir() {
            return providersDir;
        }

        public void setProvidersDir(String providersDir) {
            this.providersDir = providersDir;
        }
    }
}
