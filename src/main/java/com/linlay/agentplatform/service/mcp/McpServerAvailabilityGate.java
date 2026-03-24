package com.linlay.agentplatform.service.mcp;

import com.linlay.agentplatform.config.properties.McpProperties;
import com.linlay.agentplatform.service.remoteserver.RemoteServerAvailabilityGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class McpServerAvailabilityGate extends RemoteServerAvailabilityGate {

    @Autowired
    public McpServerAvailabilityGate(McpProperties properties) {
        this(Clock.systemUTC(), properties == null ? 60_000L : properties.getReconnectIntervalMs());
    }

    public McpServerAvailabilityGate(Clock clock, long reconnectIntervalMs) {
        super(clock, reconnectIntervalMs);
    }
}
