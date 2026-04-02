package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.linlay.agentplatform.agent.config.AgentBudgetConfig;
import com.linlay.agentplatform.agent.config.AgentModelConfig;
import com.linlay.agentplatform.agent.config.AgentRuntimePromptsConfig;
import com.linlay.agentplatform.agent.config.AgentToolConfig;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigFile {

    private String key;
    private String name;
    private JsonNode icon;
    private String description;
    private String role;
    private AgentModelConfig modelConfig;
    private AgentToolConfig toolConfig;
    private SandboxConfig sandboxConfig;
    private SkillConfig skillConfig;
    private ContextConfig contextConfig;
    private MemoryConfig memoryConfig;
    private List<AgentControl> controls;
    private AgentRuntimeMode mode;
    private List<String> promptFiles;

    private ToolChoice toolChoice;
    private AgentBudgetConfig budget;

    private OneshotConfig plain;
    private ReactConfig react;
    private PlanExecuteConfig planExecute;
    private AgentRuntimePromptsConfig runtimePrompts;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getIcon() {
        return icon;
    }

    @JsonSetter("icon")
    public void setIcon(JsonNode icon) {
        this.icon = icon;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public AgentModelConfig getModelConfig() {
        return modelConfig;
    }

    public void setModelConfig(AgentModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public AgentToolConfig getToolConfig() {
        return toolConfig;
    }

    public void setToolConfig(AgentToolConfig toolConfig) {
        this.toolConfig = toolConfig;
    }

    public SandboxConfig getSandboxConfig() {
        return sandboxConfig;
    }

    public void setSandboxConfig(SandboxConfig sandboxConfig) {
        this.sandboxConfig = sandboxConfig;
    }

    public SkillConfig getSkillConfig() {
        return skillConfig;
    }

    public void setSkillConfig(SkillConfig skillConfig) {
        this.skillConfig = skillConfig;
    }

    public ContextConfig getContextConfig() {
        return contextConfig;
    }

    public void setContextConfig(ContextConfig contextConfig) {
        this.contextConfig = contextConfig;
    }

    public MemoryConfig getMemoryConfig() {
        return memoryConfig;
    }

    public void setMemoryConfig(MemoryConfig memoryConfig) {
        this.memoryConfig = memoryConfig;
    }

    public List<AgentControl> getControls() {
        return controls;
    }

    public void setControls(List<AgentControl> controls) {
        this.controls = controls;
    }

    public AgentRuntimeMode getMode() {
        return mode;
    }

    public void setMode(AgentRuntimeMode mode) {
        this.mode = mode;
    }

    public List<String> getPromptFiles() {
        return promptFiles;
    }

    @JsonSetter("promptFile")
    public void setPromptFile(JsonNode promptFile) {
        this.promptFiles = parsePromptFiles(promptFile, "promptFile");
    }

    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(ToolChoice toolChoice) {
        this.toolChoice = toolChoice;
    }

    public AgentBudgetConfig getBudget() {
        return budget;
    }

    public void setBudget(AgentBudgetConfig budget) {
        this.budget = budget;
    }

    public OneshotConfig getPlain() {
        return plain;
    }

    public void setPlain(OneshotConfig plain) {
        this.plain = plain;
    }

    public ReactConfig getReact() {
        return react;
    }

    public void setReact(ReactConfig react) {
        this.react = react;
    }

    public PlanExecuteConfig getPlanExecute() {
        return planExecute;
    }

    public void setPlanExecute(PlanExecuteConfig planExecute) {
        this.planExecute = planExecute;
    }

    public AgentRuntimePromptsConfig getRuntimePrompts() {
        return runtimePrompts;
    }

    public void setRuntimePrompts(AgentRuntimePromptsConfig runtimePrompts) {
        this.runtimePrompts = runtimePrompts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxConfig {
        private String environmentId;
        private String level;
        private List<ExtraMountConfig> extraMounts;

        public String getEnvironmentId() {
            return environmentId;
        }

        public void setEnvironmentId(String environmentId) {
            this.environmentId = environmentId;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public List<ExtraMountConfig> getExtraMounts() {
            return extraMounts;
        }

        public void setExtraMounts(List<ExtraMountConfig> extraMounts) {
            this.extraMounts = extraMounts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExtraMountConfig {
        private String platform;
        private String source;
        private String destination;
        private String mode;

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasoningConfig {
        private Boolean enabled;
        private ComputePolicy effort;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public ComputePolicy getEffort() {
            return effort;
        }

        public void setEffort(ComputePolicy effort) {
            this.effort = effort;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillConfig {
        private List<String> skills;

        public List<String> getSkills() {
            return skills;
        }

        public void setSkills(List<String> skills) {
            this.skills = skills;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextConfig {
        private List<String> tags;

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryConfig {
        private Boolean enabled;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StageConfig {
        private String systemPrompt;
        private List<String> promptFiles;
        private boolean deepThinking;
        private AgentModelConfig modelConfig;
        private AgentToolConfig toolConfig;
        @JsonIgnore
        private boolean deepThinkingProvided;
        @JsonIgnore
        private boolean modelConfigProvided;
        @JsonIgnore
        private boolean toolConfigProvided;
        @JsonIgnore
        private boolean toolConfigExplicitNull;

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public List<String> getPromptFiles() {
            return promptFiles;
        }

        @JsonSetter("promptFile")
        public void setPromptFile(JsonNode promptFile) {
            this.promptFiles = parsePromptFiles(promptFile, "promptFile");
        }

        public boolean isDeepThinking() {
            return deepThinking;
        }

        @JsonSetter("deepThinking")
        public void setDeepThinking(boolean deepThinking) {
            this.deepThinking = deepThinking;
            this.deepThinkingProvided = true;
        }

        public AgentModelConfig getModelConfig() {
            return modelConfig;
        }

        @JsonSetter("modelConfig")
        public void setModelConfig(AgentModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            this.modelConfigProvided = true;
        }

        public AgentToolConfig getToolConfig() {
            return toolConfig;
        }

        @JsonSetter("toolConfig")
        public void setToolConfig(AgentToolConfig toolConfig) {
            this.toolConfig = toolConfig;
            this.toolConfigProvided = true;
            this.toolConfigExplicitNull = toolConfig == null;
        }

        public boolean isModelConfigProvided() {
            return modelConfigProvided;
        }

        public boolean isToolConfigProvided() {
            return toolConfigProvided;
        }

        public boolean isToolConfigExplicitNull() {
            return toolConfigExplicitNull;
        }

        public boolean isDeepThinkingProvided() {
            return deepThinkingProvided;
        }
    }

    private static List<String> parsePromptFiles(JsonNode promptFile, String fieldName) {
        if (promptFile == null || promptFile.isNull()) {
            return null;
        }
        if (promptFile.isTextual()) {
            return normalizePromptFiles(List.of(promptFile.asText()));
        }
        if (promptFile.isArray()) {
            List<String> values = new java.util.ArrayList<>();
            for (JsonNode item : promptFile) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (!item.isTextual()) {
                    throw new IllegalArgumentException(fieldName + " entries must be strings");
                }
                values.add(item.asText());
            }
            return normalizePromptFiles(values);
        }
        throw new IllegalArgumentException(fieldName + " must be a string or an array of strings");
    }

    private static List<String> normalizePromptFiles(List<String> rawPromptFiles) {
        if (rawPromptFiles == null || rawPromptFiles.isEmpty()) {
            return null;
        }
        List<String> normalized = rawPromptFiles.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(value -> value != null && !value.isEmpty())
                .toList();
        return normalized.isEmpty() ? null : normalized;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OneshotConfig extends StageConfig {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReactConfig extends StageConfig {
        private Integer maxSteps;

        public Integer getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(Integer maxSteps) {
            this.maxSteps = maxSteps;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanExecuteConfig {
        private StageConfig plan;
        private StageConfig execute;
        private StageConfig summary;
        private Integer maxSteps;

        public StageConfig getPlan() {
            return plan;
        }

        public void setPlan(StageConfig plan) {
            this.plan = plan;
        }

        public StageConfig getExecute() {
            return execute;
        }

        public void setExecute(StageConfig execute) {
            this.execute = execute;
        }

        public StageConfig getSummary() {
            return summary;
        }

        public void setSummary(StageConfig summary) {
            this.summary = summary;
        }

        public Integer getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(Integer maxSteps) {
            this.maxSteps = maxSteps;
        }
    }

}
