package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.agent.config.AgentModelConfig;
import com.linlay.agentplatform.agent.config.AgentToolConfig;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.AgentModeFactory;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.sandbox.MountAccessMode;
import com.linlay.agentplatform.agent.runtime.sandbox.SandboxLevel;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.util.RuntimeCatalogNaming;
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
import java.util.Set;
import java.util.stream.Stream;

@Component
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);
    private static final String DEFAULT_SKILL_TOOL = "_sandbox_bash_";
    private static final List<String> DEFAULT_MEMORY_TOOLS = List.of("_memory_write_", "_memory_read_", "_memory_search_");
    private static final Set<String> DEFAULT_SANDBOX_MOUNT_DESTINATIONS = Set.of(
            "/workspace",
            "/root",
            "/skills",
            "/pan",
            "/agent"
    );
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final AgentProperties properties;
    private final ModelRegistryService modelRegistryService;
    private final AgentSkillSyncService agentSkillSyncService;
    private final AgentMemoryProperties agentMemoryProperties;
    private final AgentDefaultsProperties agentDefaultsProperties;

    @Autowired
    public AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService,
            AgentSkillSyncService agentSkillSyncService,
            AgentMemoryProperties agentMemoryProperties,
            AgentDefaultsProperties agentDefaultsProperties
    ) {
        this.objectMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.properties = properties;
        this.modelRegistryService = modelRegistryService;
        this.agentSkillSyncService = agentSkillSyncService;
        this.agentMemoryProperties = agentMemoryProperties == null ? new AgentMemoryProperties() : agentMemoryProperties;
        this.agentDefaultsProperties = agentDefaultsProperties == null ? new AgentDefaultsProperties() : agentDefaultsProperties;
    }

    AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService
    ) {
        this(objectMapper, properties, modelRegistryService, null, new AgentMemoryProperties(), new AgentDefaultsProperties());
    }

    AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService,
            AgentSkillSyncService agentSkillSyncService
    ) {
        this(objectMapper, properties, modelRegistryService, agentSkillSyncService, new AgentMemoryProperties(), new AgentDefaultsProperties());
    }

    AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService,
            AgentMemoryProperties agentMemoryProperties
    ) {
        this(objectMapper, properties, modelRegistryService, null, agentMemoryProperties, new AgentDefaultsProperties());
    }

    AgentDefinitionLoader(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ModelRegistryService modelRegistryService,
            AgentDefaultsProperties agentDefaultsProperties
    ) {
        this(objectMapper, properties, modelRegistryService, null, new AgentMemoryProperties(), agentDefaultsProperties);
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

        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException ex) {
            log.warn("Cannot list external agents from {}", dir, ex);
            return List.of();
        }

        Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
        Map<String, Path> sourceFilesByAgentId = new LinkedHashMap<>();
        for (Path path : entries) {
            if (!RuntimeCatalogNaming.shouldLoadRuntimePath(path)) {
                continue;
            }
            if (!Files.isDirectory(path)) {
                continue;
            }
            tryLoadDirectoryAgent(path).ifPresent(definition -> {
                Path existing = sourceFilesByAgentId.get(definition.id());
                if (existing != null) {
                    log.warn("Skip duplicated agent key '{}' from directory {}, already loaded from {}", definition.id(), path, existing);
                    return;
                }
                loaded.put(definition.id(), definition);
                sourceFilesByAgentId.put(definition.id(), path);
            });
        }

        List<Path> flatFiles = YamlCatalogSupport.selectYamlFiles(
                entries.stream()
                        .filter(Files::isRegularFile)
                        .filter(RuntimeCatalogNaming::shouldLoadRuntimePath)
                        .toList(),
                "agent",
                log
        );
        for (Path path : flatFiles) {
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

    private Optional<AgentDefinition> tryLoadDirectoryAgent(Path agentDir) {
        Path configFile = resolveDirectoryAgentConfig(agentDir);
        if (configFile == null) {
            return Optional.empty();
        }
        String dirName = agentDir.getFileName() == null ? "" : agentDir.getFileName().toString().trim();
        Optional<AgentDefinition> loaded = tryLoadDefinition(configFile, dirName, agentDir, false);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }

        AgentDefinition definition = loaded.get();
        if (!dirName.equals(definition.id())) {
            log.warn(
                    "Skip agent because directory name must equal agent key: dir={}, key={}, path={}",
                    dirName,
                    definition.id(),
                    agentDir
            );
            return Optional.empty();
        }
        return loaded;
    }

    private Optional<AgentDefinition> tryLoadExternal(Path file) {
        String fileBasedId = RuntimeCatalogNaming.logicalBaseName(file.getFileName().toString()).trim();
        if (fileBasedId.isEmpty()) {
            log.warn("Skip external agent with empty name: {}", file);
            return Optional.empty();
        }
        return tryLoadDefinition(file, fileBasedId, null, true);
    }

    private Optional<AgentDefinition> tryLoadDefinition(
            Path file,
            String fileBasedId,
            Path agentDir,
            boolean allowKeyFallbackToFilename
    ) {
        String fileName = file.getFileName().toString();
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
            String key = normalize(config.getKey(), allowKeyFallbackToFilename ? fileBasedId : "");
            if (!StringUtils.hasText(key)) {
                log.warn("Skip agent without key in {}", file);
                return Optional.empty();
            }
            String name = normalize(config.getName(), key);
            Object icon = normalizeIcon(config.getIcon());
            String description = normalize(config.getDescription(), "external agent from " + fileName);
            String role = normalize(config.getRole(), name);
            List<String> tools = collectToolNames(config);
            List<String> skills = collectSkillNames(config);
            List<String> contextTags = collectContextTags(config);
            AgentDefinition.MemoryConfig memoryConfig = toMemoryConfig(config);
            List<AgentControl> controls = collectControls(config);
            List<String> modelKeys = collectModelKeys(config);
            AgentDefinition.SandboxConfig sandboxConfig = toSandboxConfig(config.getSandboxConfig());
            syncDeclaredSkills(agentDir, skills);
            AgentPromptFiles promptFiles = loadPromptFiles(agentDir, config, mode);
            List<String> perAgentSkills = scanPerAgentSkillIds(agentDir == null ? null : agentDir.resolve("skills"));

            AgentMode agentMode = AgentModeFactory.create(
                    mode,
                    config,
                    file,
                    promptFiles,
                    this::resolveModelByKey,
                    isMemoryFeatureEnabled(),
                    agentDefaultsProperties
            );
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
                    controls,
                    sandboxConfig,
                    modelKeys,
                    promptFiles.soulContent(),
                    promptFiles.agentsContent(),
                    contextTags,
                    perAgentSkills,
                    agentDir,
                    memoryConfig
            ));
        } catch (Exception ex) {
            log.warn("Skip invalid external agent file: {}", file, ex);
            return Optional.empty();
        }
    }

    private Path resolveDirectoryAgentConfig(Path agentDir) {
        if (agentDir == null || !Files.isDirectory(agentDir)) {
            return null;
        }
        Path yml = agentDir.resolve("agent.yml");
        if (Files.isRegularFile(yml)) {
            return yml;
        }
        Path yaml = agentDir.resolve("agent.yaml");
        if (Files.isRegularFile(yaml)) {
            return yaml;
        }
        return null;
    }

    private List<String> collectContextTags(AgentConfigFile config) {
        if (config == null || config.getContextConfig() == null) {
            return List.of();
        }
        return RuntimeContextTags.normalize(config.getContextConfig().getTags());
    }

    private AgentDefinition.MemoryConfig toMemoryConfig(AgentConfigFile config) {
        return new AgentDefinition.MemoryConfig(isAgentMemoryEnabled(config));
    }

    private String readOptionalMarkdown(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            String content = Files.readString(path);
            return StringUtils.hasText(content) ? content.trim() : null;
        } catch (IOException ex) {
            log.warn("Failed to read optional markdown: {}", path, ex);
            return null;
        }
    }

    private AgentPromptFiles loadPromptFiles(Path agentDir, AgentConfigFile config, AgentRuntimeMode mode) {
        if (agentDir == null) {
            return AgentPromptFiles.empty();
        }
        String soulContent = readOptionalMarkdown(agentDir.resolve("SOUL.md"));
        String agentsContent = readOptionalMarkdown(agentDir.resolve("AGENTS.md"));
        return switch (mode) {
            case ONESHOT -> new AgentPromptFiles(
                    soulContent,
                    agentsContent,
                    resolveDirectoryPrompt(
                            agentDir,
                            config == null ? null : config.getPromptFiles(),
                            "promptFile",
                            config == null ? null : config.getPlain(),
                            "plain.promptFile",
                            agentsContent
                    ),
                    null,
                    null,
                    null,
                    null
            );
            case REACT -> new AgentPromptFiles(
                    soulContent,
                    agentsContent,
                    null,
                    resolveDirectoryPrompt(
                            agentDir,
                            config == null ? null : config.getPromptFiles(),
                            "promptFile",
                            config == null ? null : config.getReact(),
                            "react.promptFile",
                            agentsContent
                    ),
                    null,
                    null,
                    null
            );
            case PLAN_EXECUTE -> new AgentPromptFiles(
                    soulContent,
                    agentsContent,
                    null,
                    null,
                    resolveDirectoryPrompt(
                            agentDir,
                            config == null || config.getPlanExecute() == null ? null : config.getPlanExecute().getPlan(),
                            "planExecute.plan.promptFile",
                            agentsContent
                    ),
                    resolveDirectoryPrompt(
                            agentDir,
                            config == null || config.getPlanExecute() == null ? null : config.getPlanExecute().getExecute(),
                            "planExecute.execute.promptFile",
                            agentsContent
                    ),
                    resolveDirectoryPrompt(
                            agentDir,
                            config == null || config.getPlanExecute() == null ? null : config.getPlanExecute().getSummary(),
                            "planExecute.summary.promptFile",
                            agentsContent
                    )
            );
        };
    }

    private List<String> scanPerAgentSkillIds(Path skillsDir) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> !isHiddenEntry(path))
                    .filter(RuntimeCatalogNaming::shouldLoadRuntimePath)
                    .filter(path -> Files.isRegularFile(path.resolve("SKILL.md")))
                    .filter(path -> !isScaffoldSkill(path.resolve("SKILL.md")))
                    .map(path -> normalize(path.getFileName() == null ? null : path.getFileName().toString(), "").trim().toLowerCase(Locale.ROOT))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        } catch (IOException ex) {
            log.warn("Failed to scan per-agent skills from {}", skillsDir, ex);
            return List.of();
        }
    }

    private void syncDeclaredSkills(Path agentDir, List<String> declaredSkills) {
        if (agentDir == null || agentSkillSyncService == null) {
            return;
        }
        agentSkillSyncService.reconcileDeclaredSkills(agentDir, declaredSkills);
    }

    private boolean isScaffoldSkill(Path skillFile) {
        if (skillFile == null || !Files.isRegularFile(skillFile)) {
            return false;
        }
        try {
            String raw = Files.readString(skillFile);
            String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
            if (!normalized.startsWith("---\n")) {
                return false;
            }
            int end = normalized.indexOf("\n---\n", 4);
            if (end < 0) {
                return false;
            }
            String frontmatter = normalized.substring(4, end);
            return frontmatter.lines().anyMatch(line -> "scaffold: true".equalsIgnoreCase(line.trim()));
        } catch (IOException ex) {
            log.warn("Failed to inspect per-agent skill scaffold marker: {}", skillFile, ex);
            return false;
        }
    }

    private String resolveDirectoryPrompt(
            Path agentDir,
            List<String> promptFiles,
            String fieldPath,
            AgentConfigFile.StageConfig legacyStage,
            String legacyFieldPath,
            String agentsContent
    ) {
        String configured = loadPromptMarkdowns(agentDir, promptFiles, fieldPath);
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        if (legacyStage != null) {
            String legacy = loadPromptMarkdowns(agentDir, legacyStage.getPromptFiles(), legacyFieldPath);
            if (StringUtils.hasText(legacy)) {
                return legacy;
            }
        }
        return StringUtils.hasText(agentsContent) ? agentsContent : null;
    }

    private String resolveDirectoryPrompt(
            Path agentDir,
            AgentConfigFile.StageConfig stage,
            String fieldPath,
            String agentsContent
    ) {
        if (stage != null) {
            String configured = loadPromptMarkdowns(agentDir, stage.getPromptFiles(), fieldPath);
            if (StringUtils.hasText(configured)) {
                return configured;
            }
        }
        return StringUtils.hasText(agentsContent) ? agentsContent : null;
    }

    private String loadPromptMarkdowns(Path agentDir, List<String> rawPromptFiles, String fieldPath) {
        if (rawPromptFiles == null || rawPromptFiles.isEmpty()) {
            return null;
        }
        List<String> contents = new ArrayList<>();
        for (String rawPromptFile : rawPromptFiles) {
            String content = loadPromptMarkdown(agentDir, rawPromptFile, fieldPath);
            if (StringUtils.hasText(content)) {
                contents.add(content);
            }
        }
        return contents.isEmpty() ? null : String.join("\n\n", contents);
    }

    private String loadPromptMarkdown(Path agentDir, String rawPromptFile, String fieldPath) {
        Path root = agentDir.toAbsolutePath().normalize();
        String configured = normalize(rawPromptFile, "");
        if (!StringUtils.hasText(configured)) {
            throw new IllegalArgumentException(fieldPath + " is required");
        }
        Path promptPath = Path.of(configured);
        if (promptPath.isAbsolute()) {
            throw new IllegalArgumentException(fieldPath + " must be a relative path inside the agent directory");
        }
        Path resolved = root.resolve(promptPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(fieldPath + " must stay inside the agent directory");
        }
        Path relative = root.relativize(resolved);
        for (Path segment : relative) {
            if (segment != null && segment.toString().startsWith(".")) {
                throw new IllegalArgumentException(fieldPath + " cannot reference hidden files");
            }
        }
        String fileName = resolved.getFileName() == null ? "" : resolved.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".md")) {
            throw new IllegalArgumentException(fieldPath + " must reference a .md file");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(fieldPath + " must reference an existing markdown file");
        }
        return readOptionalMarkdown(resolved);
    }

    private boolean isHiddenEntry(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        return path.getFileName().toString().startsWith(".");
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

    private boolean hasModelKey(AgentModelConfig modelConfig) {
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
        if (config != null && config.getPlain() != null) {
            merged.addAll(toolNames(config.getPlain().getToolConfig()));
        }
        if (config != null && config.getReact() != null) {
            merged.addAll(toolNames(config.getReact().getToolConfig()));
        }
        if (config != null && config.getPlanExecute() != null) {
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
        addImplicitSkillTools(merged, config);
        addImplicitMemoryTools(merged, config);
        return merged.stream().distinct().toList();
    }

    private List<String> collectSkillNames(AgentConfigFile config) {
        if (config == null || config.getSkillConfig() == null) {
            return List.of();
        }
        return normalizeNames(config.getSkillConfig().getSkills()).stream().distinct().toList();
    }

    private List<AgentControl> collectControls(AgentConfigFile config) {
        if (config == null || config.getControls() == null || config.getControls().isEmpty()) {
            return List.of();
        }
        return List.copyOf(config.getControls());
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

    private void addModelKey(List<String> merged, AgentModelConfig modelConfig) {
        if (modelConfig == null || !StringUtils.hasText(modelConfig.getModelKey())) {
            return;
        }
        String normalized = normalize(modelConfig.getModelKey(), "").trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) {
            merged.add(normalized);
        }
    }

    private List<String> toolNames(AgentToolConfig toolConfig) {
        if (toolConfig == null) {
            return List.of();
        }
        List<String> merged = new ArrayList<>();
        merged.addAll(normalizeNames(toolConfig.getBackends()));
        merged.addAll(normalizeNames(toolConfig.getFrontends()));
        merged.addAll(normalizeNames(toolConfig.getActions()));
        return merged;
    }

    private void addImplicitSkillTools(List<String> tools, AgentConfigFile config) {
        if (tools == null || !hasDeclaredSkills(config)) {
            return;
        }
        tools.add(DEFAULT_SKILL_TOOL);
    }

    private void addImplicitMemoryTools(List<String> tools, AgentConfigFile config) {
        if (tools == null || !isMemoryFeatureEnabled() || !isAgentMemoryEnabled(config)) {
            return;
        }
        tools.addAll(DEFAULT_MEMORY_TOOLS);
    }

    private boolean hasDeclaredSkills(AgentConfigFile config) {
        return config != null
                && config.getSkillConfig() != null
                && normalizeNames(config.getSkillConfig().getSkills()).stream().findAny().isPresent();
    }

    private boolean isMemoryFeatureEnabled() {
        return agentMemoryProperties == null || agentMemoryProperties.isEnabled();
    }

    private boolean isAgentMemoryEnabled(AgentConfigFile config) {
        return config != null
                && config.getMemoryConfig() != null
                && Boolean.TRUE.equals(config.getMemoryConfig().getEnabled());
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
            return new AgentDefinition.SandboxConfig(null, null, List.of());
        }
        return new AgentDefinition.SandboxConfig(
                sandboxConfig.getEnvironmentId(),
                SandboxLevel.parse(sandboxConfig.getLevel()),
                toExtraMounts(sandboxConfig.getExtraMounts())
        );
    }

    private List<AgentDefinition.ExtraMount> toExtraMounts(List<AgentConfigFile.ExtraMountConfig> extraMounts) {
        if (extraMounts == null || extraMounts.isEmpty()) {
            return List.of();
        }
        List<AgentDefinition.ExtraMount> resolved = new ArrayList<>();
        for (AgentConfigFile.ExtraMountConfig extraMount : extraMounts) {
            if (extraMount == null) {
                continue;
            }
            String platform = normalize(extraMount.getPlatform(), "");
            String source = normalize(extraMount.getSource(), "");
            String destination = normalizeSandboxDestination(extraMount.getDestination());
            MountAccessMode mode = MountAccessMode.parse(extraMount.getMode());
            if (StringUtils.hasText(platform)) {
                if (mode == null) {
                    log.warn("Skip invalid sandboxConfig.extraMounts platform item without valid mode (platform={}, mode={})",
                            platform, extraMount.getMode());
                    continue;
                }
                resolved.add(new AgentDefinition.ExtraMount(platform, null, null, mode));
                continue;
            }
            if (StringUtils.hasText(source) && StringUtils.hasText(destination)) {
                if (mode == null) {
                    log.warn("Skip invalid sandboxConfig.extraMounts custom mount without valid mode (source={}, destination={}, mode={})",
                            source, destination, extraMount.getMode());
                    continue;
                }
                resolved.add(new AgentDefinition.ExtraMount(null, source, destination, mode));
                continue;
            }
            if (StringUtils.hasText(destination) && mode != null && DEFAULT_SANDBOX_MOUNT_DESTINATIONS.contains(destination)) {
                resolved.add(new AgentDefinition.ExtraMount(null, null, destination, mode));
                continue;
            }
            log.warn("Skip invalid sandboxConfig.extraMounts item without supported platform/custom/override shape "
                    + "(platform={}, source={}, destination={}, mode={})",
                    platform, source, destination, extraMount.getMode());
        }
        return List.copyOf(resolved);
    }

    private String normalizeSandboxDestination(String destination) {
        String normalized = normalize(destination, "");
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        return Path.of(normalized).normalize().toString();
    }

    private ModelDefinition resolveModelByKey(String rawModelKey) {
        String modelKey = normalize(rawModelKey, "").trim().toLowerCase(Locale.ROOT);
        if (modelKey.isBlank()) {
            return null;
        }
        return modelRegistryService == null ? null : modelRegistryService.find(modelKey).orElse(null);
    }
}
