package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.AgentModeFactory;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.util.StringHelpers;
import com.linlay.agentplatform.util.YamlCatalogSupport;
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
import java.util.stream.Stream;

@Component
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);
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
            filesToLoad = YamlCatalogSupport.selectYamlFiles(
                    stream.filter(Files::isRegularFile).toList(),
                    "agent",
                    log
            );
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

    private Optional<AgentDefinition> tryLoadExternal(Path file) {
        String fileName = file.getFileName().toString();
        String fileBasedId = YamlCatalogSupport.fileBaseName(file).trim();
        if (fileBasedId.isEmpty()) {
            log.warn("Skip external agent with empty name: {}", file);
            return Optional.empty();
        }

        try {
            String raw = Files.readString(file);
            YamlCatalogSupport.HeaderError headerError = YamlCatalogSupport.validateHeader(
                    raw,
                    List.of("key", "name", "role", "description")
            );
            if (headerError.isPresent()) {
                log.warn("Skip agent '{}' due to invalid header: {} ({})", fileBasedId, headerError.value(), file);
                return Optional.empty();
            }
            JsonNode root = yamlMapper.readTree(raw);
            if (AgentDefinitionValidator.isLegacyConfig(root)) {
                log.warn("Skip legacy agent config {}. Only Agent Definition v2 is supported.", file);
                return Optional.empty();
            }
            if (AgentDefinitionValidator.hasRemovedFields(root)) {
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
            AgentDefinition.SandboxConfig sandboxConfig = toSandboxConfig(config.getSandboxConfig());

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
                    sandboxConfig,
                    modelKeys
            ));
        } catch (Exception ex) {
            log.warn("Skip invalid external agent file: {}", file, ex);
            return Optional.empty();
        }
    }

    private String normalize(String value, String fallback) {
        return StringHelpers.normalize(value, fallback);
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
            merged.add(PlanToolConstants.PLAN_ADD_TASKS_TOOL);
            merged.add(PlanToolConstants.PLAN_UPDATE_TASK_TOOL);
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

    private AgentDefinition.SandboxConfig toSandboxConfig(AgentConfigFile.SandboxConfig sandboxConfig) {
        if (sandboxConfig == null) {
            return new AgentDefinition.SandboxConfig(null, null);
        }
        return new AgentDefinition.SandboxConfig(
                sandboxConfig.getEnvironmentId(),
                SandboxLevel.parse(sandboxConfig.getLevel())
        );
    }

    private ModelDefinition resolveModelByKey(String rawModelKey) {
        String modelKey = normalize(rawModelKey, "").trim().toLowerCase(Locale.ROOT);
        if (modelKey.isBlank()) {
            return null;
        }
        return modelRegistryService == null ? null : modelRegistryService.find(modelKey).orElse(null);
    }
}
