package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.model.ArtifactEventPayload;
import com.linlay.agentplatform.model.api.QueryRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArtifactPublishService {

    private static final String SANDBOX_WORKSPACE_PREFIX = "/workspace";

    private final ChatDataPathService chatDataPathService;
    private final ChatAssetCatalogService chatAssetCatalogService;

    public ArtifactPublishService(
            ChatDataPathService chatDataPathService,
            ChatAssetCatalogService chatAssetCatalogService
    ) {
        this.chatDataPathService = chatDataPathService;
        this.chatAssetCatalogService = chatAssetCatalogService;
    }

    public List<Publication> publish(List<ArtifactRequest> requests, ExecutionContext context) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("artifacts must be a non-empty array");
        }
        PublishContext publishContext = requirePublishContext(context);
        List<ResolvedArtifact> resolvedArtifacts = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            ArtifactRequest request = requests.get(index);
            if (request == null) {
                throw new IllegalArgumentException("artifacts[" + index + "] must be an object");
            }
            Path sourcePath = resolveSourcePath(request.path(), publishContext.chatDir());
            if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Artifact path must point to an existing regular file: " + request.path());
            }
            resolvedArtifacts.add(new ResolvedArtifact(
                    sourcePath,
                    request.name(),
                    request.description()
            ));
        }

        List<Publication> publications = new ArrayList<>(resolvedArtifacts.size());
        for (ResolvedArtifact resolvedArtifact : resolvedArtifacts) {
            publications.add(publishResolved(resolvedArtifact, publishContext));
        }
        return List.copyOf(publications);
    }

    private PublishContext requirePublishContext(ExecutionContext context) {
        if (context == null || context.request() == null) {
            throw new IllegalArgumentException("_artifact_publish_ requires an active execution context");
        }
        ExecutionContext.ToolInvocationContext toolInvocation = context.activeToolInvocation();
        if (toolInvocation == null || !StringUtils.hasText(toolInvocation.toolId()) || !StringUtils.hasText(toolInvocation.toolName())) {
            throw new IllegalArgumentException("_artifact_publish_ requires an active tool invocation context");
        }

        String chatId = chatDataPathService.normalizeChatId(context.request().chatId());
        String runId = requireText(context.request().runId(), "runId");
        Path chatDir = chatDataPathService.resolveChatDir(chatId);
        ensureDirectory(chatDir);
        return new PublishContext(chatId, runId, chatDir);
    }

    private Publication publishResolved(ResolvedArtifact resolvedArtifact, PublishContext context) {
        Path publishedPath = resolvedArtifact.sourcePath().startsWith(context.chatDir())
                ? resolvedArtifact.sourcePath()
                : materializeIntoChatAssets(resolvedArtifact.sourcePath(), context.chatDir(), context.runId());
        String relativePath = context.chatDir().relativize(publishedPath).toString().replace('\\', '/');
        String sha256 = sha256Hex(publishedPath);

        Map<String, Object> artifactMeta = new LinkedHashMap<>();
        artifactMeta.put("origin", "tool");
        artifactMeta.put("sourcePath", resolvedArtifact.sourcePath().toString());
        artifactMeta.put("publishedPath", relativePath);
        if (StringUtils.hasText(resolvedArtifact.description())) {
            artifactMeta.put("description", resolvedArtifact.description().trim());
        }

        QueryRequest.Reference reference = chatAssetCatalogService.buildReference(
                context.chatId(),
                relativePath,
                resolvedArtifact.displayName(),
                sha256,
                artifactMeta
        );

        return new Publication(
                reference.id(),
                context.chatId(),
                context.runId(),
                reference,
                ArtifactEventPayload.fromReference(reference)
        );
    }

    public record ArtifactRequest(
            String path,
            String name,
            String description
    ) {
        public ArtifactRequest {
            path = normalizeText(path);
            name = normalizeText(name);
            description = normalizeText(description);
        }
    }

    public record Publication(
            String artifactId,
            String chatId,
            String runId,
            QueryRequest.Reference artifact,
            ArtifactEventPayload eventArtifact
    ) {
    }

    private record PublishContext(
            String chatId,
            String runId,
            Path chatDir
    ) {
    }

    private record ResolvedArtifact(
            Path sourcePath,
            String displayName,
            String description
    ) {
    }

    private Path resolveSourcePath(String rawPath, Path chatDir) {
        String normalized = requireText(rawPath, "path");
        if (normalized.startsWith(SANDBOX_WORKSPACE_PREFIX)) {
            return resolveSandboxWorkspacePath(normalized, chatDir);
        }
        Path candidate = toPath(normalized, "path");
        if (!candidate.isAbsolute()) {
            Path resolved = chatDir.resolve(candidate).normalize();
            if (!resolved.startsWith(chatDir)) {
                throw new IllegalArgumentException("Artifact relative path escapes the current chat workspace: " + rawPath);
            }
            return resolved;
        }
        Path resolved = candidate.toAbsolutePath().normalize();
        if (resolved.startsWith(chatDir) || resolved.startsWith(currentWorkspaceRoot())) {
            return resolved;
        }
        throw new IllegalArgumentException("Artifact path must be inside the current chat workspace or server workspace: " + rawPath);
    }

    private Path resolveSandboxWorkspacePath(String rawPath, Path chatDir) {
        String suffix = rawPath.substring(SANDBOX_WORKSPACE_PREFIX.length());
        while (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        }
        Path resolved = StringUtils.hasText(suffix)
                ? chatDir.resolve(suffix).normalize()
                : chatDir;
        if (!resolved.startsWith(chatDir)) {
            throw new IllegalArgumentException("Artifact sandbox path escapes the current chat workspace: " + rawPath);
        }
        return resolved;
    }

    private Path materializeIntoChatAssets(Path sourcePath, Path chatDir, String runId) {
        Path targetDir = chatDir.resolve("artifacts").resolve(runId).normalize();
        ensureDirectory(targetDir);

        String fileName = sourcePath.getFileName() == null ? "artifact" : sourcePath.getFileName().toString();
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 0;
        while (true) {
            String candidateName = counter == 0 ? fileName : baseName + "-" + counter + extension;
            Path candidate = targetDir.resolve(candidateName).normalize();
            if (!candidate.startsWith(targetDir)) {
                throw new IllegalStateException("Artifact publish target escapes chat directory");
            }
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                copyFile(sourcePath, candidate);
                return candidate;
            }
            if (sameFileContent(sourcePath, candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private boolean sameFileContent(Path left, Path right) {
        try {
            return Files.size(left) == Files.size(right) && Files.mismatch(left, right) == -1L;
        } catch (IOException ex) {
            return false;
        }
    }

    private void copyFile(Path source, Path target) {
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to publish artifact: " + source, ex);
        }
    }

    private void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare artifact directory: " + dir, ex);
        }
    }

    private Path currentWorkspaceRoot() {
        return Path.of("").toAbsolutePath().normalize();
    }

    private Path toPath(String value, String fieldName) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid argument: " + fieldName + " is not a valid path");
        }
    }

    private String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(Files.readAllBytes(path));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing argument: " + fieldName);
        }
        return value.trim();
    }

    private static String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
