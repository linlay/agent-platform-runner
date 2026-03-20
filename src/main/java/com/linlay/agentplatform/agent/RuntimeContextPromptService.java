package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linlay.agentplatform.config.ConfigDirectorySupport;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.config.RootProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RuntimeContextPromptService {

    private static final int OWNER_BODY_MAX_CHARS = 4_000;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Environment environment;
    private final RootProperties rootProperties;
    private final DataProperties dataProperties;
    private final ChatWindowMemoryProperties chatWindowMemoryProperties;

    @Autowired
    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            DataProperties dataProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties
    ) {
        this.environment = environment;
        this.rootProperties = rootProperties;
        this.dataProperties = dataProperties;
        this.chatWindowMemoryProperties = chatWindowMemoryProperties;
    }

    public RuntimeContextPromptService(
            Environment environment,
            RootProperties rootProperties,
            ChatWindowMemoryProperties chatWindowMemoryProperties
    ) {
        this(environment, rootProperties, new DataProperties(), chatWindowMemoryProperties);
    }

    public RuntimeContextPromptService() {
        this(new StandardEnvironment(), new RootProperties(), new DataProperties(), new ChatWindowMemoryProperties());
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
                case RuntimeContextTags.OWNER -> appendIfPresent(sections, buildOwnerProfileSection(runtimeContext.workspacePaths()));
                case RuntimeContextTags.AUTH -> appendIfPresent(sections, buildAuthIdentitySection(runtimeContext.authPrincipal()));
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
        Path skillsDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.skills.external-dir"), "skills-market");
        Path schedulesDir = resolveRuntimePath(runtimeHome, environment.getProperty("agent.schedule.external-dir"), "schedules");
        Path ownerFile = runtimeHome.resolve("OWNER.md").toAbsolutePath().normalize();
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
                skillsDir.toString(),
                schedulesDir.toString(),
                ownerFile.toString(),
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
                appendKeyValue(lines, "owner_file", workspacePaths.ownerFile());
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

    private String buildOwnerProfileSection(RuntimeRequestContext.WorkspacePaths workspacePaths) {
        if (workspacePaths == null || !StringUtils.hasText(workspacePaths.ownerFile())) {
            return "";
        }
        Path ownerFile = Path.of(workspacePaths.ownerFile());
        if (!Files.isRegularFile(ownerFile)) {
            return "";
        }
        try {
            String raw = Files.readString(ownerFile);
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            OwnerProfile ownerProfile = parseOwnerProfile(raw);
            if (!ownerProfile.hasContent()) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            lines.add("Runtime Context: Owner Profile");
            appendKeyValue(lines, "name", ownerProfile.headerValue("name"));
            appendKeyValue(lines, "preferred_name", ownerProfile.headerValue("preferred_name"));
            appendKeyValue(lines, "language", ownerProfile.headerValue("language"));
            appendKeyValue(lines, "timezone", ownerProfile.headerValue("timezone"));
            appendKeyValue(lines, "style", ownerProfile.headerValue("style"));
            if (StringUtils.hasText(ownerProfile.body())) {
                lines.add("owner_notes:");
                lines.add(ownerProfile.body());
            }
            return String.join("\n", lines);
        } catch (IOException ignored) {
            return "";
        }
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

    private OwnerProfile parseOwnerProfile(String raw) throws IOException {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("---")) {
            return new OwnerProfile(Map.of(), trimBody(trimmed));
        }
        int bodyStart = findFrontmatterEnd(trimmed);
        if (bodyStart < 0) {
            return new OwnerProfile(Map.of(), trimBody(trimmed));
        }
        String frontmatterRaw = trimmed.substring(3, bodyStart).trim();
        String bodyRaw = trimmed.substring(bodyStart + 4).trim();
        Map<String, Object> header = frontmatterRaw.isBlank()
                ? Map.of()
                : YAML_MAPPER.readValue(frontmatterRaw, MAP_TYPE);
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String key : List.of("name", "preferred_name", "language", "timezone", "style")) {
            Object value = header.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                normalized.put(key, String.valueOf(value).trim());
            }
        }
        return new OwnerProfile(Map.copyOf(normalized), trimBody(bodyRaw));
    }

    private int findFrontmatterEnd(String content) {
        int start = content.indexOf("\n---");
        if (start < 0) {
            return -1;
        }
        return start + 1;
    }

    private String trimBody(String bodyRaw) {
        if (!StringUtils.hasText(bodyRaw)) {
            return "";
        }
        String body = bodyRaw.trim();
        if (body.length() <= OWNER_BODY_MAX_CHARS) {
            return body;
        }
        return body.substring(0, OWNER_BODY_MAX_CHARS)
                + "\n\n[TRUNCATED: OWNER.md exceeds max chars=" + OWNER_BODY_MAX_CHARS + "]";
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
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return runtimeHome.resolve(path).toAbsolutePath().normalize();
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

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private record OwnerProfile(Map<String, String> header, String body) {
        String headerValue(String key) {
            return header == null ? null : header.get(key);
        }

        boolean hasContent() {
            return (header != null && !header.isEmpty()) || StringUtils.hasText(body);
        }
    }
}
