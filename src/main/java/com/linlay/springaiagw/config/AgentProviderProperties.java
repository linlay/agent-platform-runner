package com.linlay.springaiagw.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent.providers")
public class AgentProviderProperties {

    private ProviderConfig bailian = new ProviderConfig();
    private ProviderConfig siliconflow = new ProviderConfig();

    public ProviderConfig getBailian() {
        return bailian;
    }

    public void setBailian(ProviderConfig bailian) {
        this.bailian = bailian;
    }

    public ProviderConfig getSiliconflow() {
        return siliconflow;
    }

    public void setSiliconflow(ProviderConfig siliconflow) {
        this.siliconflow = siliconflow;
    }

    public static class ProviderConfig {
        @NotBlank
        private String baseUrl;

        @NotBlank
        private String apiKey;

        @NotBlank
        private String model;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
