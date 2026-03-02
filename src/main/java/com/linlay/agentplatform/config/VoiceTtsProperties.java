package com.linlay.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agent.voice.tts")
public class VoiceTtsProperties {

    private String provider = "synthetic";
    private AliyunProperties aliyun = new AliyunProperties();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public AliyunProperties getAliyun() {
        return aliyun;
    }

    public void setAliyun(AliyunProperties aliyun) {
        this.aliyun = aliyun == null ? new AliyunProperties() : aliyun;
    }

    public String normalizedProvider() {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase() : "synthetic";
    }

    public static class AliyunProperties {

        private String endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
        private String apiKey;
        private String model = "qwen3-tts-instruct-flash-realtime";
        private String voice = "Cherry";
        private String mode = "server_commit";
        private String languageType = "Auto";
        private String format = "pcm";
        private Integer sampleRate = 24000;
        private Float speechRate = 1.0f;
        private Integer volume = 50;
        private Float pitchRate = 1.0f;
        private Integer bitRate = 128;
        private String responseFormat = "PCM_24000HZ_MONO_16BIT";
        private String instructions = "";
        private boolean optimizeInstructions = false;
        private long sessionFinishedTimeoutMs = 120000L;
        private boolean logSentChunkEnabled = false;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
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

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getLanguageType() {
            return languageType;
        }

        public void setLanguageType(String languageType) {
            this.languageType = languageType;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public Integer getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(Integer sampleRate) {
            this.sampleRate = sampleRate;
        }

        public Float getSpeechRate() {
            return speechRate;
        }

        public void setSpeechRate(Float speechRate) {
            this.speechRate = speechRate;
        }

        public Integer getVolume() {
            return volume;
        }

        public void setVolume(Integer volume) {
            this.volume = volume;
        }

        public Float getPitchRate() {
            return pitchRate;
        }

        public void setPitchRate(Float pitchRate) {
            this.pitchRate = pitchRate;
        }

        public Integer getBitRate() {
            return bitRate;
        }

        public void setBitRate(Integer bitRate) {
            this.bitRate = bitRate;
        }

        public String getResponseFormat() {
            return responseFormat;
        }

        public void setResponseFormat(String responseFormat) {
            this.responseFormat = responseFormat;
        }

        public String getInstructions() {
            return instructions;
        }

        public void setInstructions(String instructions) {
            this.instructions = instructions;
        }

        public boolean isOptimizeInstructions() {
            return optimizeInstructions;
        }

        public void setOptimizeInstructions(boolean optimizeInstructions) {
            this.optimizeInstructions = optimizeInstructions;
        }

        public long getSessionFinishedTimeoutMs() {
            return sessionFinishedTimeoutMs;
        }

        public void setSessionFinishedTimeoutMs(long sessionFinishedTimeoutMs) {
            this.sessionFinishedTimeoutMs = sessionFinishedTimeoutMs;
        }

        public boolean isLogSentChunkEnabled() {
            return logSentChunkEnabled;
        }

        public void setLogSentChunkEnabled(boolean logSentChunkEnabled) {
            this.logSentChunkEnabled = logSentChunkEnabled;
        }

        public long safeSessionFinishedTimeoutMs() {
            return sessionFinishedTimeoutMs > 0 ? sessionFinishedTimeoutMs : 120000L;
        }
    }
}
