package com.linlay.agentplatform.config;

import com.linlay.agentplatform.model.ModelProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderConfig(
        String key,
        String baseUrl,
        String apiKey,
        String defaultModel,
        Map<ModelProtocol, ProtocolConfig> protocols
) {
    public ProviderConfig {
        protocols = protocols == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(protocols));
    }

    public ProtocolConfig getProtocol(ModelProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        return protocols.get(protocol);
    }
}
