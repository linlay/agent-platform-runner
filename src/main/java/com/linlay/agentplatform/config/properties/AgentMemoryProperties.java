package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.memory")
public class AgentMemoryProperties {

    private String dbFileName = "memory.db";
    private int contextTopN = 5;
    private int contextMaxChars = 4_000;
    private int searchDefaultLimit = 10;
    private double hybridVectorWeight = 0.7d;
    private double hybridFtsWeight = 0.3d;
    private boolean dualWriteMarkdown = true;
    private String embeddingProviderKey = "";
    private String embeddingModel = "";
    private int embeddingDimension = 1_024;
    private int embeddingTimeoutMs = 15_000;
    private Storage storage = new Storage();
    private AutoRemember autoRemember = new AutoRemember();
    private Remember remember = new Remember();

    public String getDbFileName() {
        return dbFileName;
    }

    public void setDbFileName(String dbFileName) {
        this.dbFileName = dbFileName;
    }

    public int getContextTopN() {
        return contextTopN;
    }

    public void setContextTopN(int contextTopN) {
        this.contextTopN = contextTopN;
    }

    public int getContextMaxChars() {
        return contextMaxChars;
    }

    public void setContextMaxChars(int contextMaxChars) {
        this.contextMaxChars = contextMaxChars;
    }

    public int getSearchDefaultLimit() {
        return searchDefaultLimit;
    }

    public void setSearchDefaultLimit(int searchDefaultLimit) {
        this.searchDefaultLimit = searchDefaultLimit;
    }

    public double getHybridVectorWeight() {
        return hybridVectorWeight;
    }

    public void setHybridVectorWeight(double hybridVectorWeight) {
        this.hybridVectorWeight = hybridVectorWeight;
    }

    public double getHybridFtsWeight() {
        return hybridFtsWeight;
    }

    public void setHybridFtsWeight(double hybridFtsWeight) {
        this.hybridFtsWeight = hybridFtsWeight;
    }

    public boolean isDualWriteMarkdown() {
        return dualWriteMarkdown;
    }

    public void setDualWriteMarkdown(boolean dualWriteMarkdown) {
        this.dualWriteMarkdown = dualWriteMarkdown;
    }

    public String getEmbeddingProviderKey() {
        return embeddingProviderKey;
    }

    public void setEmbeddingProviderKey(String embeddingProviderKey) {
        this.embeddingProviderKey = embeddingProviderKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public int getEmbeddingTimeoutMs() {
        return embeddingTimeoutMs;
    }

    public void setEmbeddingTimeoutMs(int embeddingTimeoutMs) {
        this.embeddingTimeoutMs = embeddingTimeoutMs;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage == null ? new Storage() : storage;
    }

    public AutoRemember getAutoRemember() {
        return autoRemember;
    }

    public void setAutoRemember(AutoRemember autoRemember) {
        this.autoRemember = autoRemember == null ? new AutoRemember() : autoRemember;
    }

    public Remember getRemember() {
        return remember;
    }

    public void setRemember(Remember remember) {
        this.remember = remember == null ? new Remember() : remember;
    }

    public static class Storage {

        private String dir = "runtime/memory";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class AutoRemember {

        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Remember {

        private String modelKey = "";
        private long timeoutMs = 60_000L;

        public String getModelKey() {
            return modelKey;
        }

        public void setModelKey(String modelKey) {
            this.modelKey = modelKey;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
