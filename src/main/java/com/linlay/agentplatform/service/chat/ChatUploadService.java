package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.model.api.UploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@Service
public class ChatUploadService {

    private static final Map<String, String> EXTRA_MIME_TYPES = createExtraMimeTypes();

    private final ChatDataPathService chatDataPathService;
    private final ChatRecordStore chatRecordStore;
    private final ConcurrentMap<String, Object> chatLocks = new ConcurrentHashMap<>();

    public ChatUploadService(ChatDataPathService chatDataPathService, ChatRecordStore chatRecordStore) {
        this.chatDataPathService = chatDataPathService;
        this.chatRecordStore = chatRecordStore;
    }

    public Mono<UploadResponse> upload(String requestId, String chatId, String sha256, FilePart filePart) {
        if (filePart == null) {
            return Mono.error(new IllegalArgumentException("file is required"));
        }
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> Mono.fromCallable(() -> uploadBytes(requestId, chatId, sha256, filePart, bytes))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private UploadResponse uploadBytes(String rawRequestId, String rawChatId, String rawSha256, FilePart filePart, byte[] bytes) {
        String requestId = requireRequestId(rawRequestId);
        String chatId = StringUtils.hasText(rawChatId)
                ? chatDataPathService.normalizeChatId(rawChatId)
                : UUID.randomUUID().toString();
        String sha256 = ChatUploadManifestStore.normalizeSha256(rawSha256);
        String originalName = originalFilename(filePart.filename());
        String mimeType = resolveMimeType(originalName, filePart.headers().getContentType());
        String type = classifyType(mimeType);
        long sizeBytes = bytes.length;
        String actualSha256 = sha256Hex(bytes);
        if (StringUtils.hasText(sha256) && !sha256.equalsIgnoreCase(actualSha256)) {
            throw new IllegalArgumentException("upload.sha256 does not match payload digest");
        }

        synchronized (lockFor(chatId)) {
            Path chatDir = ensureChatDir(chatId);
            ensureChatRecord(chatId);

            ChatUploadManifestStore.StoredUpload existing = ChatUploadManifestStore.findByRequestId(chatDir, requestId)
                    .orElse(null);
            if (existing != null) {
                if (!matchesUpload(existing, type, originalName, sizeBytes, mimeType, actualSha256)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId already exists with different payload");
                }
                Path existingPath = ChatUploadManifestStore.resolveAssetPath(chatDir, existing);
                if (existingPath.startsWith(chatDir) && Files.isRegularFile(existingPath)) {
                    return toUploadResponse(existing);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId already exists with missing upload content");
            }

            String referenceId = nextReferenceId(chatDir);
            String relativePath = resolveRelativeUploadPath(chatDir, originalName, type);
            Path assetPath = chatDir.resolve(relativePath).normalize();
            if (!assetPath.startsWith(chatDir)) {
                throw new IllegalArgumentException("upload target path is invalid");
            }
            Path tempPath = assetPath.resolveSibling(assetPath.getFileName() + ".uploading");
            long now = Instant.now().toEpochMilli();
            try {
                if (assetPath.getParent() != null) {
                    Files.createDirectories(assetPath.getParent());
                }
                Files.write(tempPath, bytes);
                moveReplacing(tempPath, assetPath);
                ChatUploadManifestStore.StoredUpload stored = new ChatUploadManifestStore.StoredUpload(
                        requestId,
                        chatId,
                        referenceId,
                        type,
                        originalName,
                        sizeBytes,
                        mimeType,
                        actualSha256,
                        relativePath,
                        ChatUploadManifestStore.STATUS_COMPLETED,
                        now,
                        now
                );
                writeManifest(chatDir, stored);
                return toUploadResponse(stored);
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception ignored) {
                    // ignore cleanup failure after upload write failure
                }
                if (ex instanceof ResponseStatusException responseStatusException) {
                    throw responseStatusException;
                }
                if (ex instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store upload", ex);
            }
        }
    }

    private UploadResponse toUploadResponse(ChatUploadManifestStore.StoredUpload stored) {
        UploadResponse.UploadTicket upload = new UploadResponse.UploadTicket(
                stored.referenceId(),
                stored.type(),
                stored.name(),
                stored.mimeType(),
                stored.sizeBytes(),
                chatDataPathService.toAssetUrl(stored.chatId(), stored.relativePath()),
                stored.sha256()
        );
        return new UploadResponse(
                stored.requestId(),
                stored.chatId(),
                upload
        );
    }

    private void ensureChatRecord(String chatId) {
        if (chatRecordStore == null) {
            return;
        }
        chatRecordStore.ensureChat(chatId, null, null, null, null);
    }

    private Path ensureChatDir(String chatId) {
        try {
            Path chatDir = chatDataPathService.resolveChatDir(chatId);
            Files.createDirectories(chatDir);
            return chatDir;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to prepare upload directory", ex);
        }
    }

    private void writeManifest(Path chatDir, ChatUploadManifestStore.StoredUpload stored) {
        try {
            ChatUploadManifestStore.write(chatDir, stored);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to persist upload reservation", ex);
        }
    }

    private String requireRequestId(String rawRequestId) {
        if (!StringUtils.hasText(rawRequestId)) {
            throw new IllegalArgumentException("requestId is required");
        }
        return rawRequestId.trim();
    }

    private boolean matchesUpload(
            ChatUploadManifestStore.StoredUpload upload,
            String type,
            String name,
            long sizeBytes,
            String mimeType,
            String actualSha256
    ) {
        return upload != null
                && type.equals(upload.type())
                && name.equals(upload.name())
                && sizeBytes == upload.sizeBytes()
                && mimeType.equals(upload.mimeType())
                && actualSha256.equalsIgnoreCase(ChatUploadManifestStore.normalizeSha256(upload.sha256()));
    }

    private String nextReferenceId(Path chatDir) {
        int maxIndex = ChatUploadManifestStore.list(chatDir).stream()
                .map(ChatUploadManifestStore.StoredUpload::referenceId)
                .filter(StringUtils::hasText)
                .filter(id -> id.matches("r\\d+"))
                .mapToInt(this::parseReferenceIndex)
                .max()
                .orElse(0);
        return "r" + String.format(Locale.ROOT, "%02d", maxIndex + 1);
    }

    private int parseReferenceIndex(String referenceId) {
        try {
            return Integer.parseInt(referenceId.substring(1));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String resolveRelativeUploadPath(Path chatDir, String originalName, String type) {
        String filename = sanitizeFilename(originalName, type);
        return uniquifyFilename(filename, usedUploadFilenames(chatDir));
    }

    private Set<String> usedUploadFilenames(Path chatDir) {
        Set<String> names = new HashSet<>();
        for (ChatUploadManifestStore.StoredUpload upload : ChatUploadManifestStore.list(chatDir)) {
            if (!StringUtils.hasText(upload.relativePath())) {
                continue;
            }
            try {
                String normalized = chatDataPathService.normalizeRelativePath(upload.relativePath());
                Path relative = Path.of(normalized);
                if (relative.getFileName() != null) {
                    names.add(relative.getFileName().toString());
                }
            } catch (IllegalArgumentException ignored) {
                // ignore malformed historical manifest entries
            }
        }
        try (Stream<Path> stream = Files.list(chatDir)) {
            stream.filter(path -> Files.isRegularFile(path))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> StringUtils.hasText(name) && !name.startsWith("."))
                    .forEach(names::add);
        } catch (Exception ignored) {
            // fall back to manifest-derived names only
        }
        Path uploadsDir = chatDir.resolve("uploads");
        if (!Files.isDirectory(uploadsDir)) {
            return names;
        }
        try (Stream<Path> stream = Files.list(uploadsDir)) {
            stream.filter(path -> Files.isRegularFile(path))
                    .map(path -> path.getFileName().toString())
                    .filter(StringUtils::hasText)
                    .forEach(names::add);
        } catch (Exception ignored) {
            // fall back to already-collected names
        }
        return names;
    }

    private String uniquifyFilename(String filename, Set<String> usedNames) {
        if (!usedNames.contains(filename)) {
            return filename;
        }
        String extension = "";
        String basename = filename;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            basename = filename.substring(0, dotIndex);
            extension = filename.substring(dotIndex);
        }
        int suffix = 2;
        while (true) {
            String candidate = basename + " (" + suffix + ")" + extension;
            if (!usedNames.contains(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private String originalFilename(String rawName) {
        String candidate = rawName == null ? "" : rawName.trim().replace('\\', '/');
        int slashIndex = candidate.lastIndexOf('/');
        if (slashIndex >= 0) {
            candidate = candidate.substring(slashIndex + 1);
        }
        candidate = Normalizer.normalize(candidate, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]+", " ")
                .trim();
        return candidate;
    }

    private String sanitizeFilename(String rawName, String type) {
        String candidate = originalFilename(rawName)
                .replaceAll("[^\\p{L}\\p{N}._()\\- ]", "_")
                .trim();
        if (!StringUtils.hasText(candidate) || ".".equals(candidate) || "..".equals(candidate)) {
            return "image".equals(type) ? "upload-image" : "upload-file";
        }
        return candidate;
    }

    private String resolveMimeType(String filename, MediaType contentType) {
        if (contentType != null) {
            return contentType.toString();
        }
        int dotIndex = filename == null ? -1 : filename.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = filename.substring(dotIndex).toLowerCase(Locale.ROOT);
            String extra = EXTRA_MIME_TYPES.get(ext);
            if (extra != null) {
                return extra;
            }
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return StringUtils.hasText(guessed) ? guessed : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private String classifyType(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("image/") ? "image" : "file";
    }

    private String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to compute upload digest", ex);
        }
    }

    private Object lockFor(String chatId) {
        return chatLocks.computeIfAbsent(chatId, ignored -> new Object());
    }

    private void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> createExtraMimeTypes() {
        Map<String, String> extraMimeTypes = new HashMap<>();
        extraMimeTypes.put(".svg", "image/svg+xml");
        extraMimeTypes.put(".webp", "image/webp");
        extraMimeTypes.put(".avif", "image/avif");
        extraMimeTypes.put(".txt", "text/plain");
        extraMimeTypes.put(".md", "text/markdown");
        extraMimeTypes.put(".csv", "text/csv");
        extraMimeTypes.put(".json", "application/json");
        extraMimeTypes.put(".pdf", "application/pdf");
        return Map.copyOf(extraMimeTypes);
    }
}
