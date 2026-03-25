package com.linlay.agentplatform.service.chat;

import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.model.api.UploadRequest;
import com.linlay.agentplatform.model.api.UploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ChatUploadService {

    private final ChatDataPathService chatDataPathService;
    private final ConcurrentMap<String, Object> chatLocks = new ConcurrentHashMap<>();

    public ChatUploadService(ChatDataPathService chatDataPathService) {
        this.chatDataPathService = chatDataPathService;
    }

    public UploadResponse reserve(UploadRequest request) {
        validateType(request.type());
        String chatId = StringUtils.hasText(request.chatId())
                ? chatDataPathService.normalizeChatId(request.chatId())
                : UUID.randomUUID().toString();
        long sizeBytes = request.sizeBytes() == null ? 0L : request.sizeBytes();
        String requestId = request.requestId().trim();
        String type = request.type().trim().toLowerCase(Locale.ROOT);
        String name = request.name().trim();
        String mimeType = request.mimeType().trim();
        String sha256 = ChatUploadManifestStore.normalizeSha256(request.sha256());

        synchronized (lockFor(chatId)) {
            Path chatDir = ensureChatDir(chatId);
            ChatUploadManifestStore.StoredUpload existing = ChatUploadManifestStore.findByRequestId(chatDir, requestId)
                    .orElse(null);
            if (existing != null) {
                if (!ChatUploadManifestStore.matchesRequest(existing, type, name, sizeBytes, mimeType, sha256)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId already exists with different payload");
                }
                return toUploadResponse(existing);
            }

            String referenceId = nextReferenceId(chatDir, type);
            String relativePath = "uploads/" + referenceId + "-" + sanitizeFilename(name, type);
            long now = Instant.now().toEpochMilli();
            ChatUploadManifestStore.StoredUpload stored = new ChatUploadManifestStore.StoredUpload(
                    requestId,
                    chatId,
                    referenceId,
                    type,
                    name,
                    sizeBytes,
                    mimeType,
                    sha256,
                    relativePath,
                    ChatUploadManifestStore.STATUS_PENDING,
                    now,
                    null
            );
            writeManifest(chatDir, stored);
            return toUploadResponse(stored);
        }
    }

    public void store(String rawChatId, String referenceId, byte[] bytes) {
        String chatId = chatDataPathService.normalizeChatId(rawChatId);
        validateReferenceId(referenceId);
        byte[] payload = bytes == null ? new byte[0] : bytes;

        synchronized (lockFor(chatId)) {
            Path chatDir = ensureChatDir(chatId);
            ChatUploadManifestStore.StoredUpload stored = ChatUploadManifestStore.load(chatDir, referenceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload reservation not found"));
            if (ChatUploadManifestStore.STATUS_COMPLETED.equals(stored.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload already completed");
            }
            if (payload.length != stored.sizeBytes()) {
                throw new IllegalArgumentException("upload.sizeBytes does not match payload length");
            }
            String actualSha256 = sha256Hex(payload);
            if (StringUtils.hasText(stored.sha256()) && !stored.sha256().equalsIgnoreCase(actualSha256)) {
                throw new IllegalArgumentException("upload.sha256 does not match payload digest");
            }

            Path assetPath = ChatUploadManifestStore.resolveAssetPath(chatDir, stored);
            if (!assetPath.startsWith(chatDir)) {
                throw new IllegalArgumentException("upload target path is invalid");
            }
            Path tempPath = assetPath.resolveSibling(assetPath.getFileName() + ".uploading");
            try {
                if (assetPath.getParent() != null) {
                    Files.createDirectories(assetPath.getParent());
                }
                Files.write(tempPath, payload);
                moveReplacing(tempPath, assetPath);
                ChatUploadManifestStore.StoredUpload completed = new ChatUploadManifestStore.StoredUpload(
                        stored.requestId(),
                        stored.chatId(),
                        stored.referenceId(),
                        stored.type(),
                        stored.name(),
                        stored.sizeBytes(),
                        stored.mimeType(),
                        stored.sha256(),
                        stored.relativePath(),
                        ChatUploadManifestStore.STATUS_COMPLETED,
                        stored.createdAt(),
                        Instant.now().toEpochMilli()
                );
                writeManifest(chatDir, completed);
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
        QueryRequest.Reference reference = new QueryRequest.Reference(
                stored.referenceId(),
                stored.type(),
                stored.name(),
                stored.mimeType(),
                stored.sizeBytes(),
                chatDataPathService.toAssetUrl(stored.chatId(), stored.relativePath()),
                stored.sha256(),
                Map.of(
                        "origin", "upload",
                        "relativePath", stored.relativePath()
                )
        );
        UploadResponse.UploadTarget uploadTarget = new UploadResponse.UploadTarget(
                "/api/upload/" + stored.chatId() + "/" + stored.referenceId(),
                "PUT",
                null
        );
        return new UploadResponse(
                stored.requestId(),
                stored.chatId(),
                reference,
                uploadTarget,
                null
        );
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

    private String nextReferenceId(Path chatDir, String type) {
        String prefix = "image".equals(type) ? "i" : "f";
        int maxIndex = ChatUploadManifestStore.list(chatDir).stream()
                .map(ChatUploadManifestStore.StoredUpload::referenceId)
                .filter(StringUtils::hasText)
                .filter(id -> id.startsWith(prefix))
                .mapToInt(this::parseReferenceIndex)
                .max()
                .orElse(0);
        return prefix + (maxIndex + 1);
    }

    private int parseReferenceIndex(String referenceId) {
        try {
            return Integer.parseInt(referenceId.substring(1));
        } catch (Exception ex) {
            return 0;
        }
    }

    private Object lockFor(String chatId) {
        return chatLocks.computeIfAbsent(chatId, ignored -> new Object());
    }

    private void validateType(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        if (!"file".equals(type) && !"image".equals(type)) {
            throw new IllegalArgumentException("type must be one of: file, image");
        }
    }

    private void validateReferenceId(String referenceId) {
        if (!StringUtils.hasText(referenceId) || !referenceId.matches("[fi]\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "referenceId is invalid");
        }
    }

    private String sanitizeFilename(String rawName, String type) {
        String candidate = rawName == null ? "" : rawName.trim().replace('\\', '/');
        int slashIndex = candidate.lastIndexOf('/');
        if (slashIndex >= 0) {
            candidate = candidate.substring(slashIndex + 1);
        }
        candidate = Normalizer.normalize(candidate, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]+", " ")
                .replaceAll("[^\\p{L}\\p{N}._()\\- ]", "_")
                .trim();
        if (!StringUtils.hasText(candidate) || ".".equals(candidate) || "..".equals(candidate)) {
            return "image".equals(type) ? "upload-image" : "upload-file";
        }
        return candidate;
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

    private void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
