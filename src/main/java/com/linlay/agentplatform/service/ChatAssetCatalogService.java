package com.linlay.agentplatform.service;

import com.linlay.agentplatform.model.api.QueryRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class ChatAssetCatalogService {

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
        try (Stream<Path> stream = Files.walk(chatDir)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(path -> toReference(normalizedChatId, chatDir, path).ifPresent(references::add));
        } catch (IOException ex) {
            return List.of();
        }
        return List.copyOf(references);
    }

    public List<QueryRequest.Reference> mergeWithChatAssets(String chatId, List<QueryRequest.Reference> requestedReferences) {
        LinkedHashMap<String, QueryRequest.Reference> merged = new LinkedHashMap<>();
        if (requestedReferences != null) {
            for (QueryRequest.Reference reference : requestedReferences) {
                putIfPresent(merged, reference);
            }
        }
        for (QueryRequest.Reference reference : listAssets(chatId)) {
            putIfPresent(merged, reference);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged.values());
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
                    Map.of("relativePath", normalizedRelativePath)
            ));
        } catch (Exception ex) {
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

    private void putIfPresent(LinkedHashMap<String, QueryRequest.Reference> merged, QueryRequest.Reference reference) {
        if (reference == null) {
            return;
        }
        merged.putIfAbsent(dedupeKey(reference), reference);
    }

    private String dedupeKey(QueryRequest.Reference reference) {
        String localAssetPath = DataFilePathNormalizer.normalizeAssetReference(reference.url());
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
