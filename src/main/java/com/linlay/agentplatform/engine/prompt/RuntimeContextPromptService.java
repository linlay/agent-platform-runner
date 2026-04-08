package com.linlay.agentplatform.engine.prompt;

import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.OwnerProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.sandbox.SandboxLevel;
import com.linlay.agentplatform.config.properties.ChatStorageProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.store.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class RuntimeContextPromptService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeContextPromptService.class);
    private static final int ALL_AGENTS_MAX_CHARS = 12_000;
    private static final String SANDBOX_WORKSPACE_DIR = "/workspace";
    private static final String SANDBOX_ROOT_DIR = "/root";
    private static final String SANDBOX_MEMORY_DIR = "/memory";
    private static final String SANDBOX_SKILLS_DIR = "/skills";
    private static final String SANDBOX_SKILLS_MARKET_DIR = "/skills-market";
    private static final String SANDBOX_PAN_DIR = "/pan";
    private static final String SANDBOX_AGENT_DIR = "/agent";

    private final Environment environment;
    private final RootProperties rootProperties;
    private final OwnerProperties ownerProperties;
    private final DataProperties dataProperties;
    private final ChatStorageProperties chatWindowMemoryProperties;
    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties agentMemoryProperties;

    @Autowired
    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            OwnerProperties ownerProperties,
            DataProperties dataProperties,
            ChatStorageProperties chatWindowMemoryProperties,
            ObjectProvider<AgentMemoryStore> agentMemoryStoreProvider,
            ObjectProvider<AgentMemoryProperties> agentMemoryPropertiesProvider
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = ownerProperties == null ? new OwnerProperties() : ownerProperties;
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.agentMemoryStore = agentMemoryStoreProvider == null ? null : agentMemoryStoreProvider.getIfAvailable();
        AgentMemoryProperties resolvedMemoryProperties = agentMemoryPropertiesProvider == null
                ? null
                : agentMemoryPropertiesProvider.getIfAvailable();
        this.agentMemoryProperties = resolvedMemoryProperties == null ? new AgentMemoryProperties() : resolvedMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            DataProperties dataProperties,
            ChatStorageProperties chatWindowMemoryProperties,
            AgentMemoryProperties agentMemoryProperties,
            RuntimeDirectoryHostPaths runtimeDirectoryHostPaths
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = new OwnerProperties();
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.agentMemoryStore = null;
        this.agentMemoryProperties = agentMemoryProperties == null ? new AgentMemoryProperties() : agentMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            OwnerProperties ownerProperties,
            DataProperties dataProperties,
            ChatStorageProperties chatWindowMemoryProperties,
            AgentMemoryProperties agentMemoryProperties,
            RuntimeDirectoryHostPaths runtimeDirectoryHostPaths
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = ownerProperties == null ? new OwnerProperties() : ownerProperties;
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
        this.agentMemoryStore = null;
        this.agentMemoryProperties = agentMemoryProperties == null ? new AgentMemoryProperties() : agentMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            ChatStorageProperties chatWindowMemoryProperties
    ) {
        this(
                environment,
                rootProperties,
                new OwnerProperties(),
                new DataProperties(),
                chatWindowMemoryProperties,
                new AgentMemoryProperties(),
                RuntimeDirectoryHostPaths.load(System.getenv())
        );
    }

    public RuntimeContextPromptService() {
        this(
                new StandardEnvironment(),
                new RootProperties(),
                new OwnerProperties(),
                new DataProperties(),
                new ChatStorageProperties(),
                new AgentMemoryProperties(),
                RuntimeDirectoryHostPaths.load(System.getenv())
        );
    }

    public String buildPrompt(AgentDefinition definition, AgentRequest request) {
        if (definition == null || definition.contextTags().isEmpty() || request == null || request.runtimeContext() == null) {
            return "";
        }
        RuntimeRequestContext runtimeContext = request.runtimeContext();
        List<String> sections = new ArrayList<>();
        for (String tag : definition.contextTags()) {
            switch (tag) {
                case RuntimeContextTags.SYSTEM -> appendIfPresent(sections, buildSystemEnvironmentSection(runtimeContext));
                case RuntimeContextTags.CONTEXT -> appendIfPresent(sections, buildContextSection(request, runtimeContext));
                case RuntimeContextTags.OWNER -> appendIfPresent(sections, buildOwnerSection(runtimeContext.localPaths()));
                case RuntimeContextTags.AUTH -> appendIfPresent(sections, buildAuthIdentitySection(runtimeContext.authPrincipal()));
                case RuntimeContextTags.SANDBOX -> appendIfPresent(sections, buildSandboxSection(runtimeContext.sandboxContext()));
                case RuntimeContextTags.ALL_AGENTS -> appendIfPresent(sections, buildAllAgentsSection(runtimeContext.agentDigests()));
                case RuntimeContextTags.MEMORY -> appendIfPresent(sections, buildAgentMemorySection(definition, request));
                default -> {
                }
            }
        }
        return sections.isEmpty() ? "" : String.join("\n\n", sections);
    }

    public RuntimeRequestContext.LocalPaths resolveLocalPaths(String chatId) {
        Path runtimeHome = resolveRuntimeHome();
        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path rootDir = resolveRuntimePath(runtimeHome, rootProperties.getExternalDir(), "root");
        Path agentsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.agents.external-dir"), "agents");
        Path chatsDir = resolveRuntimePath(runtimeHome, chatWindowMemoryProperties.getDir(), "chats");
        Path memoryDir = resolveRuntimePath(runtimeHome, agentMemoryProperties.getStorage().getDir(), "memory");
        Path dataDir = resolveRuntimePath(runtimeHome, dataProperties.getExternalDir(), "data");
        Path skillsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.skills.external-dir"), null);
        Path schedulesDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.schedule.external-dir"), null);
        Path ownerDir = resolveOwnerDir(runtimeHome);
        Path attachmentsDir = StringUtils.hasText(chatId)
                ? dataDir.resolve(chatId.trim()).toAbsolutePath().normalize()
                : dataDir.toAbsolutePath().normalize();
        return new RuntimeRequestContext.LocalPaths(
                runtimeHome.toString(),
                workingDirectory.toString(),
                rootDir.toString(),
                agentsDir.toString(),
                chatsDir.toString(),
                memoryDir == null ? null : memoryDir.toString(),
                dataDir.toString(),
                pathValue(skillsDir),
                pathValue(schedulesDir),
                ownerDir.toString(),
                attachmentsDir.toString()
        );
    }

    public RuntimeRequestContext.SandboxPaths resolveSandboxPaths(
            AgentDefinition definition,
            String chatId,
            String defaultSandboxLevel
    ) {
        Path runtimeHome = resolveRuntimeHome();
        Path rootDir = resolveRuntimePath(runtimeHome, rootProperties.getExternalDir(), "root");
        Path panDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.pan.external-dir"), null);
        Path skillsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.skills.external-dir"), null);
        Path agentsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.agents.external-dir"), "agents");
        Path ownerDir = resolveOwnerDir(runtimeHome);
        Path teamsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.teams.external-dir"), null);
        Path schedulesDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.schedule.external-dir"), null);
        Path chatsDir = resolveRuntimePath(runtimeHome, chatWindowMemoryProperties.getDir(), "chats");
        Path memoryDir = resolveRuntimePath(runtimeHome, agentMemoryProperties.getStorage().getDir(), "memory");
        Path modelsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.models.external-dir"), null);
        Path providersDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.providers.external-dir"), null);
        Path mcpServersDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.mcp-servers.registry.external-dir"), null);
        Path viewportServersDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.viewport-servers.registry.external-dir"), null);
        Path toolsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.tools.external-dir"), null);
        Path viewportsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.viewports.external-dir"), null);
        SandboxLevel level = resolveSandboxLevel(definition, defaultSandboxLevel);
        boolean hasAgentSelfDir = hasAgentSelfDir(agentsDir, definition == null ? null : definition.id());
        boolean hasGlobalSkillsDir = skillsDir != null;
        boolean hasSkillsDir = level == SandboxLevel.GLOBAL ? hasGlobalSkillsDir : (hasAgentSelfDir || hasGlobalSkillsDir);
        String skillsMarketMount = null;

        String ownerMount = null;
        String agentsMount = null;
        String teamsMount = null;
        String schedulesMount = null;
        String chatsMount = null;
        String memoryMount = null;
        String modelsMount = null;
        String providersMount = null;
        String mcpServersMount = null;
        String viewportServersMount = null;
        String toolsMount = null;
        String viewportsMount = null;

        if (definition != null && definition.sandboxConfig() != null) {
            for (AgentDefinition.ExtraMount extraMount : definition.sandboxConfig().extraMounts()) {
                if (extraMount == null || !extraMount.isPlatform()) {
                    continue;
                }
                String platform = extraMount.platform().trim().toLowerCase(Locale.ROOT);
                switch (platform) {
                    case "skills-market" -> skillsMarketMount = skillsDir == null ? null : SANDBOX_SKILLS_MARKET_DIR;
                    case "owner" -> ownerMount = ownerDir == null ? null : "/owner";
                    case "agents" -> agentsMount = agentsDir == null ? null : "/agents";
                    case "teams" -> teamsMount = teamsDir == null ? null : "/teams";
                    case "schedules" -> schedulesMount = schedulesDir == null ? null : "/schedules";
                    case "chats" -> chatsMount = chatsDir == null ? null : "/chats";
                    case "memory" -> memoryMount = memoryDir == null ? null : SANDBOX_MEMORY_DIR;
                    case "models" -> modelsMount = modelsDir == null ? null : "/models";
                    case "providers" -> providersMount = providersDir == null ? null : "/providers";
                    case "mcp-servers" -> mcpServersMount = mcpServersDir == null ? null : "/mcp-servers";
                    case "viewport-servers" -> viewportServersMount = viewportServersDir == null ? null : "/viewport-servers";
                    case "tools" -> toolsMount = toolsDir == null ? null : "/tools";
                    case "viewports" -> viewportsMount = viewportsDir == null ? null : "/viewports";
                    default -> {
                    }
                }
            }
        }

        return new RuntimeRequestContext.SandboxPaths(
                resolveSandboxCwd(level, chatId),
                SANDBOX_WORKSPACE_DIR,
                rootDir == null ? null : SANDBOX_ROOT_DIR,
                hasSkillsDir ? SANDBOX_SKILLS_DIR : null,
                skillsMarketMount,
                panDir == null ? null : SANDBOX_PAN_DIR,
                hasAgentSelfDir ? SANDBOX_AGENT_DIR : null,
                ownerMount,
                agentsMount,
                teamsMount,
                schedulesMount,
                chatsMount,
                memoryMount,
                modelsMount,
                providersMount,
                mcpServersMount,
                viewportServersMount,
                toolsMount,
                viewportsMount
        );
    }

    private String buildSystemEnvironmentSection(RuntimeRequestContext runtimeContext) {
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: System Environment");
        lines.add("os: " + valueOrUnknown(System.getProperty("os.name"))
                + " " + valueOrUnknown(System.getProperty("os.version")));
        lines.add("arch: " + valueOrUnknown(System.getProperty("os.arch")));
        lines.add("java_version: " + valueOrUnknown(System.getProperty("java.version")));
        Locale locale = Locale.getDefault();
        lines.add("timezone: " + ZonedDateTime.now().getZone());
        lines.add("locale: " + locale.toLanguageTag());
        lines.add("current_date: " + LocalDate.now().toString());
        lines.add("current_datetime: " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return String.join("\n", lines);
    }

    private String buildContextSection(AgentRequest request, RuntimeRequestContext runtimeContext) {
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: Context");
        if (runtimeContext != null) {
            RuntimeRequestContext.SandboxPaths sandboxPaths = runtimeContext.sandboxPaths();
            if (sandboxPaths != null) {
                appendKeyValue(lines, "sandbox_workspace_dir", sandboxPaths.workspaceDir());
                appendKeyValue(lines, "sandbox_root_dir", sandboxPaths.rootDir());
                appendKeyValue(lines, "sandbox_skills_dir", sandboxPaths.skillsDir());
                appendKeyValue(lines, "sandbox_skills_market_dir", sandboxPaths.skillsMarketDir());
                appendKeyValue(lines, "sandbox_pan_dir", sandboxPaths.panDir());
                appendKeyValue(lines, "sandbox_agent_dir", sandboxPaths.agentDir());
                appendKeyValue(lines, "sandbox_owner_dir", sandboxPaths.ownerDir());
                appendKeyValue(lines, "sandbox_agents_dir", sandboxPaths.agentsDir());
                appendKeyValue(lines, "sandbox_teams_dir", sandboxPaths.teamsDir());
                appendKeyValue(lines, "sandbox_schedules_dir", sandboxPaths.schedulesDir());
                appendKeyValue(lines, "sandbox_chats_dir", sandboxPaths.chatsDir());
                appendKeyValue(lines, "sandbox_memory_dir", sandboxPaths.memoryDir());
                appendKeyValue(lines, "sandbox_models_dir", sandboxPaths.modelsDir());
                appendKeyValue(lines, "sandbox_providers_dir", sandboxPaths.providersDir());
                appendKeyValue(lines, "sandbox_mcp_servers_dir", sandboxPaths.mcpServersDir());
                appendKeyValue(lines, "sandbox_viewport_servers_dir", sandboxPaths.viewportServersDir());
                appendKeyValue(lines, "sandbox_tools_dir", sandboxPaths.toolsDir());
                appendKeyValue(lines, "sandbox_viewports_dir", sandboxPaths.viewportsDir());
            }
        }
        appendKeyValue(lines, "chatId", request.chatId());
        appendKeyValue(lines, "requestId", request.requestId());
        appendKeyValue(lines, "runId", request.runId());
        if (runtimeContext != null) {
            appendKeyValue(lines, "agentKey", runtimeContext.agentKey());
            appendKeyValue(lines, "teamId", runtimeContext.teamId());
            if (runtimeContext.scene() != null) {
                String scene = summarizeScene(runtimeContext.scene());
                if (StringUtils.hasText(scene)) {
                    lines.add("scene: " + scene);
                }
            }
            appendReferences(lines, runtimeContext.references());
        }
        return String.join("\n", lines);
    }

    private String buildOwnerSection(RuntimeRequestContext.LocalPaths localPaths) {
        if (localPaths == null || !StringUtils.hasText(localPaths.ownerDir())) {
            return "";
        }
        Path ownerDir = Path.of(localPaths.ownerDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(ownerDir)) {
            return "";
        }
        List<Path> markdownFiles = collectOwnerMarkdownFiles(ownerDir);
        if (markdownFiles.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: Owner");
        for (Path file : markdownFiles) {
            Path relativePath = ownerDir.relativize(file);
            lines.add("--- file: " + normalizeRelativePath(relativePath));
            try {
                lines.add(Files.readString(file));
            } catch (IOException ex) {
                log.debug(
                        "Failed to read owner markdown file path={}, fallback=emit unreadable placeholder",
                        file,
                        ex
                );
                lines.add("[UNREADABLE: " + normalizeRelativePath(relativePath) + "]");
            }
        }
        return String.join("\n", lines);
    }

    private String buildAuthIdentitySection(JwksJwtVerifier.JwtPrincipal principal) {
        if (principal == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: Auth Identity");
        appendKeyValue(lines, "subject", principal.subject());
        appendKeyValue(lines, "deviceId", principal.deviceId());
        appendKeyValue(lines, "scope", principal.scope());
        if (principal.issuedAt() != null) {
            lines.add("issuedAt: " + principal.issuedAt());
        }
        if (principal.expiresAt() != null) {
            lines.add("expiresAt: " + principal.expiresAt());
        }
        return String.join("\n", lines);
    }

    private String buildSandboxSection(RuntimeRequestContext.SandboxContext sandboxContext) {
        if (sandboxContext == null) {
            return "";
        }
        if (!StringUtils.hasText(sandboxContext.environmentPrompt())) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: Sandbox");
        appendKeyValue(lines, "environmentId", sandboxContext.environmentId());
        appendKeyValue(lines, "configuredEnvironmentId", sandboxContext.configuredEnvironmentId());
        appendKeyValue(lines, "defaultEnvironmentId", sandboxContext.defaultEnvironmentId());
        appendKeyValue(lines, "level", sandboxContext.level());
        lines.add("container_hub_enabled: " + sandboxContext.containerHubEnabled());
        lines.add("uses_sandbox_bash: " + sandboxContext.usesContainerHubTool());
        if (sandboxContext.extraMounts() != null && !sandboxContext.extraMounts().isEmpty()) {
            lines.add("extraMounts:");
            for (String extraMount : sandboxContext.extraMounts()) {
                if (StringUtils.hasText(extraMount)) {
                    lines.add("- " + extraMount.trim());
                }
            }
        }
        lines.add("environment_prompt:");
        lines.add(sandboxContext.environmentPrompt().trim());
        return String.join("\n", lines);
    }

    private String buildAllAgentsSection(List<RuntimeRequestContext.AgentDigest> agentDigests) {
        if (agentDigests == null || agentDigests.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        int totalChars = 0;
        int included = 0;
        int total = (int) agentDigests.stream()
                .filter(agentDigest -> agentDigest != null && StringUtils.hasText(agentDigest.key()))
                .count();
        for (RuntimeRequestContext.AgentDigest agentDigest : agentDigests) {
            if (agentDigest == null || !StringUtils.hasText(agentDigest.key())) {
                continue;
            }
            String block = formatAgentDigest(agentDigest);
            if (!StringUtils.hasText(block)) {
                continue;
            }
            int projected = totalChars + block.length() + (blocks.isEmpty() ? 0 : 6);
            if (projected > ALL_AGENTS_MAX_CHARS) {
                break;
            }
            blocks.add(block);
            totalChars = projected;
            included++;
        }
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Runtime Context: All Agents\n");
        builder.append(String.join("\n---\n", blocks));
        if (included < total) {
            builder.append("\n[TRUNCATED: all-agents exceeds max chars=")
                    .append(ALL_AGENTS_MAX_CHARS)
                    .append(", included=")
                    .append(included)
                    .append("/")
                    .append(total)
                    .append("]");
        }
        return builder.toString();
    }

    private String buildAgentMemorySection(AgentDefinition definition, AgentRequest request) {
        if (definition == null || agentMemoryStore == null || agentMemoryProperties == null) {
            return "";
        }
        List<MemoryRecord> memories = StringUtils.hasText(request.message())
                ? agentMemoryStore.topRelevant(definition.id(), definition.agentDir(), request.message(), agentMemoryProperties.getContextTopN())
                : agentMemoryStore.list(
                definition.id(),
                definition.agentDir(),
                null,
                agentMemoryProperties.getContextTopN(),
                "importance"
        );
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Runtime Context: Agent Memory\n");
        boolean first = true;
        for (MemoryRecord memory : memories) {
            if (!first) {
                builder.append("---\n");
            }
            first = false;
            builder.append("id: ").append(memory.id()).append('\n');
            builder.append("subjectKey: ").append(memory.subjectKey()).append('\n');
            builder.append("sourceType: ").append(memory.sourceType()).append('\n');
            builder.append("category: ").append(memory.category()).append('\n');
            builder.append("importance: ").append(memory.importance()).append('\n');
            if (!memory.tags().isEmpty()) {
                builder.append("tags: ").append(String.join(", ", memory.tags())).append('\n');
            }
            builder.append("content: ").append(memory.content()).append('\n');
        }
        return truncate(builder.toString().trim(), agentMemoryProperties.getContextMaxChars(), "agent-memory");
    }

    private String formatAgentDigest(RuntimeRequestContext.AgentDigest agentDigest) {
        List<String> lines = new ArrayList<>();
        appendKeyValue(lines, "key", agentDigest.key());
        appendKeyValue(lines, "name", agentDigest.name());
        appendKeyValue(lines, "role", agentDigest.role());
        appendKeyValue(lines, "description", agentDigest.description());
        appendKeyValue(lines, "mode", agentDigest.mode());
        appendKeyValue(lines, "modelKey", agentDigest.modelKey());
        appendInlineList(lines, "tools", agentDigest.tools());
        appendInlineList(lines, "skills", agentDigest.skills());
        if (agentDigest.sandbox() != null
                && (StringUtils.hasText(agentDigest.sandbox().environmentId()) || StringUtils.hasText(agentDigest.sandbox().level()))) {
            lines.add("sandbox:");
            appendIndentedKeyValue(lines, "environmentId", agentDigest.sandbox().environmentId());
            appendIndentedKeyValue(lines, "level", agentDigest.sandbox().level());
        }
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    private List<Path> collectOwnerMarkdownFiles(Path ownerDir) {
        try (Stream<Path> stream = Files.walk(ownerDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isOwnerMarkdownFile)
                    .sorted(Comparator.comparing(path -> normalizeRelativePath(ownerDir.relativize(path))))
                    .toList();
        } catch (IOException ex) {
            log.debug(
                    "Failed to scan owner markdown files ownerDir={}, fallback=empty owner context",
                    ownerDir,
                    ex
            );
            return List.of();
        }
    }

    private boolean isOwnerMarkdownFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private String normalizeRelativePath(Path relativePath) {
        if (relativePath == null) {
            return "";
        }
        return relativePath.toString().replace('\\', '/');
    }

    private String truncate(String content, int maxChars, String label) {
        if (!StringUtils.hasText(content) || maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }
        String suffix = "\n[TRUNCATED: " + label + " exceeds max chars=" + maxChars + "]";
        int end = Math.max(0, maxChars - suffix.length());
        return content.substring(0, end) + suffix;
    }

    private Path resolveRuntimeHome() {
        Path configDir = ConfigDirectorySupport.resolveConfigDirectory()
                .orElse(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().resolve("configs"));
        Path parent = configDir.toAbsolutePath().normalize().getParent();
        return parent == null
                ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : parent;
    }

    private Path resolveRuntimePath(Path runtimeHome, String configured, String fallback) {
        String raw = StringUtils.hasText(configured) ? configured.trim() : fallback;
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return runtimeHome.resolve(path).toAbsolutePath().normalize();
    }

    private Path resolveOwnerDir(Path runtimeHome) {
        String configured = ownerProperties == null ? null : ownerProperties.getExternalDir();
        return resolveRuntimePath(runtimeHome, configured, "owner");
    }

    private SandboxLevel resolveSandboxLevel(AgentDefinition definition, String defaultSandboxLevel) {
        if (definition != null && definition.sandboxConfig() != null && definition.sandboxConfig().level() != null) {
            return definition.sandboxConfig().level();
        }
        if (!StringUtils.hasText(defaultSandboxLevel)) {
            return SandboxLevel.RUN;
        }
        try {
            return SandboxLevel.valueOf(defaultSandboxLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SandboxLevel.RUN;
        }
    }

    private boolean hasAgentSelfDir(Path agentsDir, String agentKey) {
        if (agentsDir == null || !StringUtils.hasText(agentKey)) {
            return false;
        }
        return Files.isDirectory(agentsDir.resolve(agentKey.trim()).toAbsolutePath().normalize());
    }

    private String resolveSandboxCwd(SandboxLevel level, String chatId) {
        if (level == SandboxLevel.RUN || !StringUtils.hasText(chatId)) {
            return SANDBOX_WORKSPACE_DIR;
        }
        return SANDBOX_WORKSPACE_DIR + "/" + chatId.trim();
    }

    private String pathValue(Path path) {
        return path == null ? null : path.toString();
    }

    private String summarizeScene(QueryRequest.Scene scene) {
        List<String> parts = new ArrayList<>();
        if (scene == null) {
            return "";
        }
        if (StringUtils.hasText(scene.title())) {
            parts.add("title=" + scene.title().trim());
        }
        if (StringUtils.hasText(scene.url())) {
            parts.add("url=" + scene.url().trim());
        }
        return String.join(", ", parts);
    }

    private void appendReferences(List<String> lines, List<QueryRequest.Reference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        lines.add("references:");
        for (QueryRequest.Reference reference : references) {
            if (reference == null) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            appendReferenceField(fields, "id", reference.id());
            appendReferenceField(fields, "sandboxPath", reference.sandboxPath());
            appendReferenceField(fields, "name", reference.name());
            if (reference.sizeBytes() != null) {
                fields.add("sizeBytes: " + reference.sizeBytes());
            }
            appendReferenceField(fields, "mimeType", reference.mimeType());
            if (fields.isEmpty()) {
                continue;
            }
            lines.add("  - " + fields.getFirst());
            for (int i = 1; i < fields.size(); i++) {
                lines.add("    " + fields.get(i));
            }
        }
    }

    private void appendReferenceField(List<String> fields, String key, String value) {
        if (StringUtils.hasText(value)) {
            fields.add(key + ": " + sanitizeYamlScalar(value.trim()));
        }
    }

    private String sanitizeYamlScalar(String value) {
        return value
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private void appendIfPresent(List<String> sections, String content) {
        if (StringUtils.hasText(content)) {
            sections.add(content.trim());
        }
    }

    private void appendKeyValue(List<String> lines, String key, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(key + ": " + value.trim());
        }
    }

    private void appendIndentedKeyValue(List<String> lines, String key, String value) {
        if (StringUtils.hasText(value)) {
            lines.add("  " + key + ": " + value.trim());
        }
    }

    private void appendInlineList(List<String> lines, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> normalized = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (normalized.isEmpty()) {
            return;
        }
        lines.add(key + ": [" + String.join(", ", normalized) + "]");
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }
}
