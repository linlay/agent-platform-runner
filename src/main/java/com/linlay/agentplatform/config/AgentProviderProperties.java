package com.linlay.agentplatform.config;

import com.linlay.agentplatform.model.ModelProtocol;
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
        private String baseUrl;
        private String apiKey;
        private String model;
        private Map<ModelProtocol, ProtocolConfig> protocols = new LinkedHashMap<>();

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

        public Map<ModelProtocol, ProtocolConfig> getProtocols() {
            return protocols;
        }

        public void setProtocols(Map<ModelProtocol, ProtocolConfig> protocols) {
            this.protocols = protocols == null ? new LinkedHashMap<>() : new LinkedHashMap<>(protocols);
        }

        public ProtocolConfig getProtocol(ModelProtocol protocol) {
            if (protocol == null || protocols == null) {
                return null;
            }
            return protocols.get(protocol);
        }
    }

    public static class ProtocolConfig {
        private String endpointPath;

        public String getEndpointPath() {
            return endpointPath;
        }

        public void setEndpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
        }
    }
}
