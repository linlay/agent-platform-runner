package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.OwnerProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;
import java.util.stream.Stream;

@Component
public class RuntimeContextPromptService {

    private static final int ALL_AGENTS_MAX_CHARS = 12_000;

    private final Environment environment;
    private final RootProperties rootProperties;
    private final OwnerProperties ownerProperties;
    private final DataProperties dataProperties;
    private final ChatWindowMemoryProperties chatWindowMemoryProperties;

    @Autowired
    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            OwnerProperties ownerProperties,
            DataProperties dataProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = ownerProperties == null ? new OwnerProperties() : ownerProperties;
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            DataProperties dataProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            RuntimeDirectoryHostPaths runtimeDirectoryHostPaths
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = new OwnerProperties();
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            OwnerProperties ownerProperties,
            DataProperties dataProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties,
            RuntimeDirectoryHostPaths runtimeDirectoryHostPaths
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.ownerProperties = ownerProperties == null ? new OwnerProperties() : ownerProperties;
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties
    ) {
        this(
                environment,
                rootProperties,
                new OwnerProperties(),
                new DataProperties(),
                chatWindowMemoryProperties,
                RuntimeDirectoryHostPaths.load(System.getenv())
        );
    }

    public RuntimeContextPromptService() {
        this(
                new StandardEnvironment(),
                new RootProperties(),
                new OwnerProperties(),
                new DataProperties(),
                new ChatWindowMemoryProperties(),
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
                case RuntimeContextTags.OWNER -> appendIfPresent(sections, buildOwnerSection(runtimeContext.workspacePaths()));
                case RuntimeContextTags.AUTH -> appendIfPresent(sections, buildAuthIdentitySection(runtimeContext.authPrincipal()));
                case RuntimeContextTags.SANDBOX -> appendIfPresent(sections, buildSandboxSection(runtimeContext.sandboxContext()));
                case RuntimeContextTags.ALL_AGENTS -> appendIfPresent(sections, buildAllAgentsSection(runtimeContext.agentDigests()));
                default -> {
                }
            }
        }
        return sections.isEmpty() ? "" : String.join("\n\n", sections);
    }

    public RuntimeRequestContext.WorkspacePaths resolveWorkspacePaths(String chatId) {
        Path runtimeHome = resolveRuntimeHome();
        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path rootDir = resolveRuntimePath(runtimeHome, rootProperties.getExternalDir(), "root");
        Path agentsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.agents.external-dir"), "agents");
        Path chatsDir = resolveRuntimePath(runtimeHome, chatWindowMemoryProperties.getDir(), "chats");
        Path dataDir = resolveRuntimePath(runtimeHome, dataProperties.getExternalDir(), "data");
        Path skillsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.skills.external-dir"), null);
        Path schedulesDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.schedule.external-dir"), null);
        Path ownerDir = resolveOwnerDir(runtimeHome);
        Path attachmentsDir = StringUtils.hasText(chatId)
                ? dataDir.resolve(chatId.trim()).toAbsolutePath().normalize()
                : dataDir.toAbsolutePath().normalize();
        return new RuntimeRequestContext.WorkspacePaths(
                runtimeHome.toString(),
                workingDirectory.toString(),
                rootDir.toString(),
                agentsDir.toString(),
                chatsDir.toString(),
                dataDir.toString(),
                pathValue(skillsDir),
                pathValue(schedulesDir),
                ownerDir.toString(),
                attachmentsDir.toString()
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
        if (runtimeContext != null && runtimeContext.workspacePaths() != null && StringUtils.hasText(runtimeContext.workspacePaths().workingDirectory())) {
            lines.add("runner_working_directory: " + runtimeContext.workspacePaths().workingDirectory());
        }
        return String.join("\n", lines);
    }

    private String buildContextSection(AgentRequest request, RuntimeRequestContext runtimeContext) {
        List<String> lines = new ArrayList<>();
        lines.add("Runtime Context: Context");
        if (runtimeContext != null) {
            RuntimeRequestContext.WorkspacePaths workspacePaths = runtimeContext.workspacePaths();
            if (workspacePaths != null) {
                appendKeyValue(lines, "runtime_home", workspacePaths.runtimeHome());
                appendKeyValue(lines, "root_dir", workspacePaths.rootDir());
                appendKeyValue(lines, "agents_dir", workspacePaths.agentsDir());
                appendKeyValue(lines, "chats_dir", workspacePaths.chatsDir());
                appendKeyValue(lines, "data_dir", workspacePaths.dataDir());
                appendKeyValue(lines, "skills_market_dir", workspacePaths.skillsDir());
                appendKeyValue(lines, "schedules_dir", workspacePaths.schedulesDir());
                appendKeyValue(lines, "owner_dir", workspacePaths.ownerDir());
                appendKeyValue(lines, "chat_attachments_dir", workspacePaths.chatAttachmentsDir());
            }
        }
        appendKeyValue(lines, "chatId", request.chatId());
        appendKeyValue(lines, "requestId", request.requestId());
        appendKeyValue(lines, "runId", request.runId());
        if (runtimeContext != null) {
            appendKeyValue(lines, "agentKey", runtimeContext.agentKey());
            appendKeyValue(lines, "teamId", runtimeContext.teamId());
            appendKeyValue(lines, "role", runtimeContext.role());
            appendKeyValue(lines, "chatName", runtimeContext.chatName());
            if (runtimeContext.scene() != null) {
                String scene = summarizeScene(runtimeContext.scene());
                if (StringUtils.hasText(scene)) {
                    lines.add("scene: " + scene);
                }
            }
            String referenceSummary = summarizeReferences(runtimeContext.references());
            if (StringUtils.hasText(referenceSummary)) {
                lines.add("references: " + referenceSummary);
            }
        }
        return String.join("\n", lines);
    }

    private String buildOwnerSection(RuntimeRequestContext.WorkspacePaths workspacePaths) {
        if (workspacePaths == null || !StringUtils.hasText(workspacePaths.ownerDir())) {
            return "";
        }
        Path ownerDir = Path.of(workspacePaths.ownerDir()).toAbsolutePath().normalize();
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
            } catch (IOException ignored) {
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
        } catch (IOException ignored) {
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

    private String summarizeReferences(List<QueryRequest.Reference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (QueryRequest.Reference reference : references) {
            if (reference == null) {
                continue;
            }
            StringBuilder item = new StringBuilder();
            if (StringUtils.hasText(reference.name())) {
                item.append(reference.name().trim());
            } else if (StringUtils.hasText(reference.id())) {
                item.append(reference.id().trim());
            } else {
                item.append("reference");
            }
            if (StringUtils.hasText(reference.type())) {
                item.append(" (").append(reference.type().trim()).append(")");
            }
            items.add(item.toString());
            if (items.size() >= 5) {
                break;
            }
        }
        String summary = String.join("; ", items);
        if (references.size() > items.size()) {
            summary = summary + "; +" + (references.size() - items.size()) + " more";
        }
        return references.size() + " item(s): " + summary;
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
