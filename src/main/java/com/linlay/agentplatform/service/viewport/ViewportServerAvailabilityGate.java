package com.linlay.agentplatform.service.viewport;

import com.linlay.agentplatform.config.properties.ViewportServerProperties;
import com.linlay.agentplatform.service.remoteserver.RemoteServerAvailabilityGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class ViewportServerAvailabilityGate extends RemoteServerAvailabilityGate {

    @Autowired
    public ViewportServerAvailabilityGate(ViewportServerProperties properties) {
        this(Clock.systemUTC(), properties == null ? 60_000L : properties.getReconnectIntervalMs());
    }

    public ViewportServerAvailabilityGate(Clock clock, long reconnectIntervalMs) {
        super(clock, reconnectIntervalMs);
    }
}
