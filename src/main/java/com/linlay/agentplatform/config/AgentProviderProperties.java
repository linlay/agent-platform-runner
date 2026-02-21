package com.linlay.agentplatform.config;

import com.linlay.agentplatform.model.ProviderProtocol;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProviderProperties {

    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public ProviderConfig getProvider(String key) {
        return providers.get(key);
    }

    public static class ProviderConfig {
        private ProviderProtocol protocol = ProviderProtocol.OPENAI_COMPATIBLE;
        private String baseUrl;
        private String apiKey;
        private String model;

        public ProviderProtocol getProtocol() {
            return protocol;
        }

        public void setProtocol(ProviderProtocol protocol) {
            this.protocol = protocol;
        }

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
