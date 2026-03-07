package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.AgentModeFactory;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.model.ModelDefinition;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.model.ModelRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);
    private static final String PLAN_ADD_TASK_TOOL = "_plan_add_tasks_";
    private static final String PLAN_UPDATE_TASK_TOOL = "_plan_update_task_";
    private static final Set<String> YAML_EXTENSIONS = Set.of(".yml", ".yaml");
    private static final Pattern MULTILINE_PROMPT_PATTERN =
            Pattern.compile("(\"[a-zA-Z0-9_]*systemPrompt\"\\s*:\\s*)\"\"\"([\\s\\S]*?)\"\"\"", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final AgentProperties properties;
    private final ModelRegistryService modelRegistryService;

    @Autowired
    public AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        this.modelRegistryService = modelRegistryService;
    }

    public List<AgentDefinition> loadAll() {
        Path dir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            log.debug("External agents dir does not exist, skip loading: {}", dir);
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            log.warn("Configured external agents path is not a directory: {}", dir);
            return List.of();
        }

        List<Path> filesToLoad;
        try (Stream<Path> stream = Files.list(dir)) {
            filesToLoad = selectFilesToLoad(stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedAgentFile)
                    .toList());
        } catch (IOException ex) {
            log.warn("Cannot list external agents from {}", dir, ex);
            return List.of();
        }

        Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
        Map<String, Path> sourceFilesByAgentId = new LinkedHashMap<>();
        for (Path path : filesToLoad) {
            tryLoadExternal(path).ifPresent(definition -> {
                Path existing = sourceFilesByAgentId.get(definition.id());
                if (existing != null) {
                    log.warn("Skip duplicated agent key '{}' from file {}, already loaded from {}", definition.id(), path, existing);
                    return;
                }
                loaded.put(definition.id(), definition);
                sourceFilesByAgentId.put(definition.id(), path);
            });
        }

        if (!loaded.isEmpty()) {
            log.debug("Loaded {} external agents from {}", loaded.size(), dir);
        }
        return new ArrayList<>(loaded.values());
    }

    private List<Path> selectFilesToLoad(List<Path> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Comparator<Path> priorityComparator = Comparator
                .comparingInt(this::formatPriority)
                .thenComparing(path -> path.getFileName().toString());

        Map<String, List<Path>> byBaseName = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            byBaseName.computeIfAbsent(fileBaseName(candidate), ignored -> new ArrayList<>()).add(candidate);
        }

        List<Path> selected = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : byBaseName.entrySet()) {
            List<Path> files = new ArrayList<>(entry.getValue());
            files.sort(priorityComparator);
            Path chosen = files.getFirst();
            if (files.size() > 1) {
                List<String> ignored = files.stream()
                        .skip(1)
                        .map(path -> path.getFileName().toString())
                        .toList();
                log.warn(
                        "Found multiple agent files for basename '{}', choose '{}' and ignore {}",
                        entry.getKey(),
                        chosen.getFileName(),
                        ignored
                );
            }
            selected.add(chosen);
        }
        selected.sort(priorityComparator);
        return List.copyOf(selected);
    }

    private Optional<AgentDefinition> tryLoadExternal(Path file) {
        String fileName = file.getFileName().toString();
        String fileBasedId = fileBaseName(file).trim();
        if (fileBasedId.isEmpty()) {
            log.warn("Skip external agent with empty name: {}", file);
            return Optional.empty();
        }

        try {
            JsonNode root = parseConfig(file);
            if (isLegacyConfig(root)) {
                log.warn("Skip legacy agent config {}. Only Agent Definition v2 is supported.", file);
                return Optional.empty();
            }
            if (hasRemovedFields(root)) {
                log.warn("Skip agent config {}. Removed fields are no longer supported.", file);
                return Optional.empty();
            }

            AgentConfigFile config = objectMapper.treeToValue(root, AgentConfigFile.class);
            AgentRuntimeMode mode = config.getMode();
            if (mode == null) {
                log.warn("Skip agent without mode in {}", file);
                return Optional.empty();
            }
            if (!hasAnyModelConfig(config)) {
                log.warn("Skip agent without modelConfig (top-level or stage-level) in {}", file);
                return Optional.empty();
            }

            ModelDefinition primaryModel = resolvePrimaryModel(config).orElse(null);
            if (primaryModel == null) {
                log.warn("Skip agent without resolvable modelKey in {}", file);
                return Optional.empty();
            }
            String key = normalize(config.getKey(), fileBasedId);
            String name = normalize(config.getName(), key);
            Object icon = normalizeIcon(config.getIcon());
            String description = normalize(config.getDescription(), "external agent from " + fileName);
            String role = normalize(config.getRole(), name);
            List<String> tools = collectToolNames(config);
            List<String> skills = collectSkillNames(config);
            List<String> modelKeys = collectModelKeys(config);

            AgentMode agentMode = AgentModeFactory.create(mode, config, file, this::resolveModelByKey);
            RunSpec runSpec = agentMode.defaultRunSpec(config);

            return Optional.of(new AgentDefinition(
                    key,
                    name,
                    icon,
                    description,
                    role,
                    primaryModel.key(),
                    primaryModel.provider(),
                    primaryModel.modelId(),
                    primaryModel.protocol(),
                    mode,
                    runSpec,
                    agentMode,
                    tools,
                    skills,
                    modelKeys
            ));
        } catch (Exception ex) {
            log.warn("Skip invalid external agent file: {}", file, ex);
            return Optional.empty();
        }
    }

    private JsonNode parseConfig(Path file) throws IOException {
        String raw = Files.readString(file);
        if (isYamlFile(file)) {
            return yamlMapper.readTree(raw);
        }
        String normalizedJson = normalizeMultilinePrompts(raw);
        return objectMapper
                .reader()
                .with(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())
                .with(JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature())
                .readTree(normalizedJson);
    }

    private boolean isSupportedAgentFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".json") || YAML_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private boolean isYamlFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return YAML_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private int formatPriority(Path file) {
        return isYamlFile(file) ? 0 : 1;
    }

    private String fileBaseName(Path file) {
        String fileName = file.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".yaml")) {
            return fileName.substring(0, fileName.length() - ".yaml".length());
        }
        if (lowerName.endsWith(".yml")) {
            return fileName.substring(0, fileName.length() - ".yml".length());
        }
        if (lowerName.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - ".json".length());
        }
        return fileName;
    }

    private boolean isLegacyConfig(JsonNode root) {
        if (root == null || !root.isObject()) {
            return true;
        }
        if (root.has("deepThink")
                || root.has("systemPrompt")
                || root.has("providerKey")
                || root.has("model")
                || root.has("reasoning")
                || root.has("tools")) {
            return true;
        }
        return hasLegacyStageFields(root.path("plain"))
                || hasLegacyStageFields(root.path("react"))
                || hasLegacyPlanExecuteStageFields(root.path("planExecute"));
    }

    private boolean hasLegacyPlanExecuteStageFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return hasLegacyStageFields(node.path("plan"))
                || hasLegacyStageFields(node.path("execute"))
                || hasLegacyStageFields(node.path("summary"));
    }

    private boolean hasLegacyStageFields(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("providerKey")
                || node.has("model")
                || node.has("reasoning")
                || node.has("tools");
    }

    private boolean hasRemovedFields(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (hasLegacyModelConfigFields(root.path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("plain").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("react").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("plan").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("execute").path("modelConfig"))
                || hasLegacyModelConfigFields(root.path("planExecute").path("summary").path("modelConfig"))) {
            return true;
        }
        if (root.has("verify") || root.has("output") || root.has("toolPolicy")) {
            return true;
        }
        JsonNode budget = root.path("budget");
        if (budget.isObject() && budget.has("maxSteps")) {
            return true;
        }
        JsonNode runtimePrompts = root.path("runtimePrompts");
        if (!runtimePrompts.isObject()) {
            return false;
        }
        if (runtimePrompts.has("verify")
                || runtimePrompts.has("finalAnswer")
                || runtimePrompts.has("oneshot")
                || runtimePrompts.has("react")) {
            return true;
        }
        JsonNode planExecute = runtimePrompts.path("planExecute");
        if (!planExecute.isObject()) {
            return false;
        }
        return planExecute.has("executeToolsTitle")
                || planExecute.has("planCallableToolsTitle")
                || planExecute.has("draftInstructionBlock")
                || planExecute.has("generateInstructionBlockFromDraft")
                || planExecute.has("generateInstructionBlockDirect")
                || planExecute.has("taskRequireToolUserPrompt")
                || planExecute.has("taskMultipleToolsUserPrompt")
                || planExecute.has("taskUpdateNoProgressUserPrompt")
                || planExecute.has("taskContinueUserPrompt")
                || planExecute.has("updateRoundPromptTemplate")
                || planExecute.has("updateRoundMultipleToolsUserPrompt")
                || planExecute.has("allStepsCompletedUserPrompt");
    }

    private boolean hasLegacyModelConfigFields(JsonNode modelConfig) {
        if (modelConfig == null || !modelConfig.isObject()) {
            return false;
        }
        return modelConfig.has("providerKey") || modelConfig.has("model");
    }

    private String normalizeMultilinePrompts(String rawJson) throws IOException {
        Matcher matcher = MULTILINE_PROMPT_PATTERN.matcher(rawJson);
        if (!matcher.find()) {
            return rawJson;
        }

        StringBuffer rewritten = new StringBuffer();
        do {
            String content = stripOuterLineBreak(matcher.group(2));
            String escaped = objectMapper.writeValueAsString(content);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + escaped));
        } while (matcher.find());
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String stripOuterLineBreak(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasAnyModelConfig(AgentConfigFile config) {
        if (config == null) {
            return false;
        }
        if (hasModelKey(config.getModelConfig())) {
            return true;
        }
        if (config.getPlain() != null && hasModelKey(config.getPlain().getModelConfig())) {
            return true;
        }
        if (config.getReact() != null && hasModelKey(config.getReact().getModelConfig())) {
            return true;
        }
        if (config.getPlanExecute() == null) {
            return false;
        }
        return hasStageModelConfig(config.getPlanExecute().getPlan())
                || hasStageModelConfig(config.getPlanExecute().getExecute())
                || hasStageModelConfig(config.getPlanExecute().getSummary());
    }

    private boolean hasStageModelConfig(AgentConfigFile.StageConfig stageConfig) {
        return stageConfig != null && hasModelKey(stageConfig.getModelConfig());
    }

    private boolean hasModelKey(AgentConfigFile.ModelConfig modelConfig) {
        return modelConfig != null && StringUtils.hasText(modelConfig.getModelKey());
    }

    private List<String> normalizeNames(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : rawValues) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            normalized.add(raw.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private Object normalizeIcon(JsonNode icon) {
        if (icon == null || icon.isNull()) {
            return null;
        }
        if (icon.isTextual()) {
            String value = icon.asText();
            if (!StringUtils.hasText(value)) {
                return null;
            }
            return value.trim();
        }
        if (!icon.isObject()) {
            return null;
        }
        String name = icon.path("name").asText(null);
        String color = icon.path("color").asText(null);
        if (!StringUtils.hasText(name) && !StringUtils.hasText(color)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) {
            normalized.put("name", name.trim());
        }
        if (StringUtils.hasText(color)) {
            normalized.put("color", color.trim());
        }
        return normalized;
    }

    private List<String> collectToolNames(AgentConfigFile config) {
        List<String> merged = new ArrayList<>(toolNames(config == null ? null : config.getToolConfig()));
        if (config.getPlain() != null) {
            merged.addAll(toolNames(config.getPlain().getToolConfig()));
        }
        if (config.getReact() != null) {
            merged.addAll(toolNames(config.getReact().getToolConfig()));
        }
        if (config.getPlanExecute() != null) {
            if (config.getPlanExecute().getPlan() != null) {
                merged.addAll(toolNames(config.getPlanExecute().getPlan().getToolConfig()));
            }
            if (config.getPlanExecute().getExecute() != null) {
                merged.addAll(toolNames(config.getPlanExecute().getExecute().getToolConfig()));
            }
            if (config.getPlanExecute().getSummary() != null) {
                merged.addAll(toolNames(config.getPlanExecute().getSummary().getToolConfig()));
            }
        }
        AgentRuntimeMode mode = config == null ? null : config.getMode();
        if (mode == AgentRuntimeMode.PLAN_EXECUTE) {
            merged.add(PLAN_ADD_TASK_TOOL);
            merged.add(PLAN_UPDATE_TASK_TOOL);
        }
        return merged.stream().distinct().toList();
    }

    private List<String> collectSkillNames(AgentConfigFile config) {
        if (config == null) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        merged.addAll(normalizeNames(config.getSkills()));
        if (config.getSkillConfig() != null) {
            merged.addAll(normalizeNames(config.getSkillConfig().getSkills()));
        }
        return merged.stream().distinct().toList();
    }

    private List<String> collectModelKeys(AgentConfigFile config) {
        if (config == null) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        addModelKey(merged, config.getModelConfig());
        if (config.getPlain() != null) {
            addModelKey(merged, config.getPlain().getModelConfig());
        }
        if (config.getReact() != null) {
            addModelKey(merged, config.getReact().getModelConfig());
        }
        if (config.getPlanExecute() != null) {
            if (config.getPlanExecute().getPlan() != null) {
                addModelKey(merged, config.getPlanExecute().getPlan().getModelConfig());
            }
            if (config.getPlanExecute().getExecute() != null) {
                addModelKey(merged, config.getPlanExecute().getExecute().getModelConfig());
            }
            if (config.getPlanExecute().getSummary() != null) {
                addModelKey(merged, config.getPlanExecute().getSummary().getModelConfig());
            }
        }
        return merged.stream().distinct().toList();
    }

    private void addModelKey(List<String> merged, AgentConfigFile.ModelConfig modelConfig) {
        if (modelConfig == null || !StringUtils.hasText(modelConfig.getModelKey())) {
            return;
        }
        String normalized = normalize(modelConfig.getModelKey(), "").trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            merged.add(normalized);
        }
    }

    private List<String> toolNames(AgentConfigFile.ToolConfig toolConfig) {
        if (toolConfig == null) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        merged.addAll(normalizeNames(toolConfig.getBackends()));
        merged.addAll(normalizeNames(toolConfig.getFrontends()));
        merged.addAll(normalizeNames(toolConfig.getActions()));
        return merged;
    }

    private Optional<ModelDefinition> resolvePrimaryModel(AgentConfigFile config) {
        String primaryKey = resolvePrimaryModelKey(config);
        if (!StringUtils.hasText(primaryKey)) {
            return Optional.empty();
        }
        ModelDefinition resolved = resolveModelByKey(primaryKey);
        return Optional.ofNullable(resolved);
    }

    private String resolvePrimaryModelKey(AgentConfigFile config) {
        if (config == null) {
            return null;
        }
        if (config.getModelConfig() != null && StringUtils.hasText(config.getModelConfig().getModelKey())) {
            return config.getModelConfig().getModelKey();
        }
        if (config.getPlain() != null && config.getPlain().getModelConfig() != null
                && StringUtils.hasText(config.getPlain().getModelConfig().getModelKey())) {
            return config.getPlain().getModelConfig().getModelKey();
        }
        if (config.getReact() != null && config.getReact().getModelConfig() != null
                && StringUtils.hasText(config.getReact().getModelConfig().getModelKey())) {
            return config.getReact().getModelConfig().getModelKey();
        }
        if (config.getPlanExecute() == null) {
            return null;
        }
        String fromPlan = resolveStageModelKey(config.getPlanExecute().getPlan());
        if (StringUtils.hasText(fromPlan)) {
            return fromPlan;
        }
        String fromExecute = resolveStageModelKey(config.getPlanExecute().getExecute());
        if (StringUtils.hasText(fromExecute)) {
            return fromExecute;
        }
        return resolveStageModelKey(config.getPlanExecute().getSummary());
    }

    private String resolveStageModelKey(AgentConfigFile.StageConfig stageConfig) {
        if (stageConfig == null || stageConfig.getModelConfig() == null) {
            return null;
        }
        return stageConfig.getModelConfig().getModelKey();
    }

    private ModelDefinition resolveModelByKey(String rawModelKey) {
        String modelKey = normalize(rawModelKey, "").trim().toLowerCase(Locale.ROOT);
        if (modelKey.isBlank()) {
            return null;
        }
        return modelRegistryService == null ? null : modelRegistryService.find(modelKey).orElse(null);
    }
}
