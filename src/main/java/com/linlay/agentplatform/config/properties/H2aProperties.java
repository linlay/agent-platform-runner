package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.h2a")
public class H2aProperties {

    private final Render render = new Render();

    public Render getRender() {
        return render;
    }

    public static class Render {
        private long flushIntervalMs = 0L;
        private int maxBufferedChars = 0;
        private int maxBufferedEvents = 0;
        private boolean heartbeatPassThrough = true;

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getMaxBufferedChars() {
            return maxBufferedChars;
        }

        public void setMaxBufferedChars(int maxBufferedChars) {
            this.maxBufferedChars = maxBufferedChars;
        }

        public int getMaxBufferedEvents() {
            return maxBufferedEvents;
        }

        public void setMaxBufferedEvents(int maxBufferedEvents) {
            this.maxBufferedEvents = maxBufferedEvents;
        }

        public boolean isHeartbeatPassThrough() {
            return heartbeatPassThrough;
        }

        public void setHeartbeatPassThrough(boolean heartbeatPassThrough) {
            this.heartbeatPassThrough = heartbeatPassThrough;
        }
    }
}
