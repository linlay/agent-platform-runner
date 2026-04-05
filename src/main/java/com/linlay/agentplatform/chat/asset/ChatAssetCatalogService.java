package com.linlay.agentplatform.chat.asset;

import com.linlay.agentplatform.chat.upload.ChatUploadManifestStore;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.util.ResourcePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ChatAssetCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ChatAssetCatalogService.class);
    private static final Map<String, String> EXTRA_MIME_TYPES = Map.ofEntries(
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".avif", "image/avif"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".mp3", "audio/mpeg"),
            Map.entry(".wav", "audio/wav"),
            Map.entry(".m4a", "audio/mp4"),
            Map.entry(".flac", "audio/flac"),
            Map.entry(".ogg", "audio/ogg")
    );

    private final ChatDataPathService chatDataPathService;

    public ChatAssetCatalogService(ChatDataPathService chatDataPathService) {
        this.chatDataPathService = chatDataPathService;
    }

    public List<QueryRequest.Reference> listAssets(String chatId) {
        String normalizedChatId = chatDataPathService.normalizeChatId(chatId);
        Path chatDir = chatDataPathService.resolveChatDir(normalizedChatId);
        if (!Files.isDirectory(chatDir)) {
            return List.of();
        }

        List<QueryRequest.Reference> references = new ArrayList<>();
        Set<String> manifestRelativePaths = new LinkedHashSet<>();
        for (ChatUploadManifestStore.StoredUpload upload : ChatUploadManifestStore.list(chatDir)) {
            if (!ChatUploadManifestStore.STATUS_COMPLETED.equals(upload.status())) {
                continue;
            }
            if (!shouldExpose(upload.relativePath())) {
                continue;
            }
            Path assetPath = ChatUploadManifestStore.resolveAssetPath(chatDir, upload);
            if (!assetPath.startsWith(chatDir) || !Files.isRegularFile(assetPath, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            manifestRelativePaths.add(upload.relativePath());
            references.add(new QueryRequest.Reference(
                    upload.referenceId(),
                    upload.type(),
                    upload.name(),
                    upload.mimeType(),
                    upload.sizeBytes(),
                    chatDataPathService.toAssetUrl(normalizedChatId, upload.relativePath()),
                    upload.sha256(),
                    chatDataPathService.toSandboxWorkspacePath(upload.relativePath()),
                    Map.of(
                            "origin", "upload",
                            "relativePath", upload.relativePath()
                    )
            ));
        }
        try (Stream<Path> stream = Files.walk(chatDir)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(path -> {
                        String relativePath = chatDir.relativize(path).toString().replace('\\', '/');
                        if (manifestRelativePaths.contains(relativePath)) {
                            return;
                        }
                        toReference(normalizedChatId, chatDir, path).ifPresent(references::add);
                    });
        } catch (IOException ex) {
            log.debug(
                    "Failed to scan chat asset directory chatId={}, chatDir={}, fallback=manifest-only assets",
                    normalizedChatId,
                    chatDir,
                    ex
            );
            return List.copyOf(references);
        }
        return List.copyOf(references);
    }

    public List<QueryRequest.Reference> mergeWithChatAssets(String chatId, List<QueryRequest.Reference> requestedReferences) {
        LinkedHashMap<String, QueryRequest.Reference> merged = new LinkedHashMap<>();
        if (requestedReferences != null) {
            for (QueryRequest.Reference reference : requestedReferences) {
                putIfPresent(merged, normalizeReference(chatId, reference));
            }
        }
        for (QueryRequest.Reference reference : listAssets(chatId)) {
            putIfPresent(merged, reference);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged.values());
    }

    public QueryRequest.Reference buildReference(
            String chatId,
            String relativePath,
            String displayName,
            String sha256,
            Map<String, Object> meta
    ) {
        String normalizedChatId = chatDataPathService.normalizeChatId(chatId);
        String normalizedRelativePath = chatDataPathService.normalizeRelativePath(relativePath);
        Path chatDir = chatDataPathService.resolveChatDir(normalizedChatId);
        Path file = chatDir.resolve(normalizedRelativePath).normalize();
        if (!file.startsWith(chatDir) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Artifact file not found in chat assets: " + normalizedRelativePath);
        }
        String mimeType = guessContentType(file);
        Map<String, Object> mergedMeta = meta == null || meta.isEmpty()
                ? Map.of("relativePath", normalizedRelativePath)
                : mergeMeta(meta, normalizedRelativePath);
        return new QueryRequest.Reference(
                stableReferenceId(normalizedChatId, normalizedRelativePath),
                classifyType(mimeType),
                StringUtils.hasText(displayName) ? displayName.trim() : file.getFileName().toString(),
                mimeType,
                sizeOf(file),
                chatDataPathService.toAssetUrl(normalizedChatId, normalizedRelativePath),
                StringUtils.hasText(sha256) ? sha256.trim() : null,
                chatDataPathService.toSandboxWorkspacePath(normalizedRelativePath),
                mergedMeta
        );
    }

    private java.util.Optional<QueryRequest.Reference> toReference(String chatId, Path chatDir, Path file) {
        try {
            Path relativePath = chatDir.relativize(file);
            String normalizedRelativePath = relativePath.toString().replace('\\', '/');
            if (!shouldExpose(normalizedRelativePath)) {
                return java.util.Optional.empty();
            }
            String mimeType = guessContentType(file);
            return java.util.Optional.of(new QueryRequest.Reference(
                    stableReferenceId(chatId, normalizedRelativePath),
                    classifyType(mimeType),
                    file.getFileName().toString(),
                    mimeType,
                    Files.size(file),
                    chatDataPathService.toAssetUrl(chatId, normalizedRelativePath),
                    null,
                    chatDataPathService.toSandboxWorkspacePath(normalizedRelativePath),
                    Map.of("relativePath", normalizedRelativePath)
            ));
        } catch (Exception ex) {
            log.debug(
                    "Failed to build chat asset reference chatId={}, path={}, fallback=skip file",
                    chatId,
                    file,
                    ex
            );
            return java.util.Optional.empty();
        }
    }

    private boolean shouldExpose(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return false;
        }
        String[] segments = relativePath.split("/");
        for (String segment : segments) {
            if (!StringUtils.hasText(segment) || segment.startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private String guessContentType(Path file) {
        String filename = file.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = filename.substring(dotIndex).toLowerCase(Locale.ROOT);
            String extra = EXTRA_MIME_TYPES.get(ext);
            if (extra != null) {
                return extra;
            }
        }
        try {
            String probed = Files.probeContentType(file);
            if (StringUtils.hasText(probed)) {
                return probed;
            }
        } catch (IOException ignored) {
            // ignore probe failures and fall back to name-based inference
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return StringUtils.hasText(guessed) ? guessed : "application/octet-stream";
    }

    private String classifyType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) {
            return "image";
        }
        if (normalized.startsWith("audio/")) {
            return "audio";
        }
        if (normalized.startsWith("video/")) {
            return "video";
        }
        return "file";
    }

    private String stableReferenceId(String chatId, String relativePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((chatId + ":" + relativePath).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("asset_");
            for (int i = 0; i < Math.min(12, bytes.length); i++) {
                builder.append(String.format("%02x", bytes[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "asset_" + Math.abs((chatId + ":" + relativePath).hashCode());
        }
    }

    private Long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return null;
        }
    }

    private Map<String, Object> mergeMeta(Map<String, Object> meta, String relativePath) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(meta);
        merged.putIfAbsent("relativePath", relativePath);
        return Map.copyOf(merged);
    }

    private void putIfPresent(LinkedHashMap<String, QueryRequest.Reference> merged, QueryRequest.Reference reference) {
        if (reference == null) {
            return;
        }
        String key = dedupeKey(reference);
        QueryRequest.Reference existing = merged.get(key);
        merged.put(key, existing == null ? reference : mergeReferences(existing, reference));
    }

    private QueryRequest.Reference normalizeReference(String chatId, QueryRequest.Reference reference) {
        if (reference == null) {
            return null;
        }
        String sandboxPath = normalizedSandboxPath(reference.sandboxPath());
        if (!StringUtils.hasText(sandboxPath)) {
            String relativePath = resolveRelativePath(chatId, reference);
            if (StringUtils.hasText(relativePath)) {
                sandboxPath = chatDataPathService.toSandboxWorkspacePath(relativePath);
            }
        }
        if (sameText(reference.sandboxPath(), sandboxPath)) {
            return reference;
        }
        return new QueryRequest.Reference(
                reference.id(),
                reference.type(),
                reference.name(),
                reference.mimeType(),
                reference.sizeBytes(),
                reference.url(),
                reference.sha256(),
                sandboxPath,
                reference.meta()
        );
    }

    private QueryRequest.Reference mergeReferences(QueryRequest.Reference primary, QueryRequest.Reference secondary) {
        Map<String, Object> mergedMeta = mergeMetaMaps(primary.meta(), secondary.meta());
        return new QueryRequest.Reference(
                firstNonBlank(primary.id(), secondary.id()),
                firstNonBlank(primary.type(), secondary.type()),
                firstNonBlank(primary.name(), secondary.name()),
                firstNonBlank(primary.mimeType(), secondary.mimeType()),
                primary.sizeBytes() != null ? primary.sizeBytes() : secondary.sizeBytes(),
                firstNonBlank(primary.url(), secondary.url()),
                firstNonBlank(primary.sha256(), secondary.sha256()),
                firstNonBlank(primary.sandboxPath(), secondary.sandboxPath()),
                mergedMeta
        );
    }

    private Map<String, Object> mergeMetaMaps(Map<String, Object> primary, Map<String, Object> secondary) {
        if ((primary == null || primary.isEmpty()) && (secondary == null || secondary.isEmpty())) {
            return null;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (secondary != null) {
            merged.putAll(secondary);
        }
        if (primary != null) {
            merged.putAll(primary);
        }
        return Map.copyOf(merged);
    }

    private String resolveRelativePath(String chatId, QueryRequest.Reference reference) {
        String metaRelativePath = relativePathFromMeta(reference.meta());
        if (StringUtils.hasText(metaRelativePath)) {
            return metaRelativePath;
        }
        if (!StringUtils.hasText(chatId)) {
            return null;
        }
        String normalizedAssetPath = ResourcePathNormalizer.normalizeAssetReference(reference.url());
        if (!chatDataPathService.belongsToChat(chatId, normalizedAssetPath)) {
            return null;
        }
        try {
            Path assetPath = Path.of(normalizedAssetPath);
            if (assetPath.getNameCount() < 2) {
                return null;
            }
            return assetPath.subpath(1, assetPath.getNameCount()).toString().replace('\\', '/');
        } catch (Exception ex) {
            return null;
        }
    }

    private String relativePathFromMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Object relativePath = meta.get("relativePath");
        if (relativePath instanceof String text && StringUtils.hasText(text)) {
            try {
                return chatDataPathService.normalizeRelativePath(text);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        Object filePath = meta.get("filePath");
        if (!(filePath instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("/workspace/")) {
            trimmed = trimmed.substring("/workspace/".length());
        } else if (trimmed.startsWith("/")) {
            return null;
        }
        try {
            return chatDataPathService.normalizeRelativePath(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizedSandboxPath(String sandboxPath) {
        if (!StringUtils.hasText(sandboxPath)) {
            return null;
        }
        String trimmed = sandboxPath.trim();
        if (trimmed.startsWith("/workspace/")) {
            return chatDataPathService.toSandboxWorkspacePath(trimmed.substring("/workspace/".length()));
        }
        return trimmed;
    }

    private String firstNonBlank(String primary, String secondary) {
        if (StringUtils.hasText(primary)) {
            return primary.trim();
        }
        return StringUtils.hasText(secondary) ? secondary.trim() : null;
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = StringUtils.hasText(left) ? left.trim() : null;
        String normalizedRight = StringUtils.hasText(right) ? right.trim() : null;
        return java.util.Objects.equals(normalizedLeft, normalizedRight);
    }

    private String dedupeKey(QueryRequest.Reference reference) {
        String localAssetPath = ResourcePathNormalizer.normalizeAssetReference(reference.url());
        if (StringUtils.hasText(localAssetPath)) {
            return "asset:" + localAssetPath;
        }
        if (StringUtils.hasText(reference.id())) {
            return "id:" + reference.id().trim();
        }
        if (StringUtils.hasText(reference.url())) {
            return "url:" + reference.url().trim();
        }
        if (StringUtils.hasText(reference.name())) {
            return "name:" + reference.name().trim();
        }
        return "ref:" + System.identityHashCode(reference);
    }
}
