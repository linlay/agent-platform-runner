package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "agent.voice.ws")
public class VoiceWsProperties {

    private boolean enabled = false;
    private boolean authRequired = true;
    private String path = "/api/ap/ws/voice";
    private List<String> codecs = new ArrayList<>(List.of("pcm"));
    private long maxSessionSeconds = 300L;
    private int defaultSampleRate = 24000;
    private int defaultChannels = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getCodecs() {
        return codecs;
    }

    public void setCodecs(List<String> codecs) {
        this.codecs = codecs == null ? new ArrayList<>() : new ArrayList<>(codecs);
    }

    public long getMaxSessionSeconds() {
        return maxSessionSeconds;
    }

    public void setMaxSessionSeconds(long maxSessionSeconds) {
        this.maxSessionSeconds = maxSessionSeconds;
    }

    public int getDefaultSampleRate() {
        return defaultSampleRate;
    }

    public void setDefaultSampleRate(int defaultSampleRate) {
        this.defaultSampleRate = defaultSampleRate;
    }

    public int getDefaultChannels() {
        return defaultChannels;
    }

    public void setDefaultChannels(int defaultChannels) {
        this.defaultChannels = defaultChannels;
    }

    public String normalizedPath() {
        if (!StringUtils.hasText(path)) {
            return "/api/ap/ws/voice";
        }
        String normalized = path.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    public boolean declaresCodec(String codec) {
        if (!StringUtils.hasText(codec)) {
            return false;
        }
        String normalized = codec.trim().toLowerCase(Locale.ROOT);
        return codecs.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    public int safeDefaultSampleRate() {
        return defaultSampleRate > 0 ? defaultSampleRate : 24000;
    }

    public int safeDefaultChannels() {
        return defaultChannels > 0 ? defaultChannels : 1;
    }

    public long safeMaxSessionSeconds() {
        return maxSessionSeconds > 0 ? maxSessionSeconds : 300L;
    }
}
