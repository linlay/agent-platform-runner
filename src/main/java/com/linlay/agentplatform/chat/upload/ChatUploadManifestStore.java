package com.linlay.agentplatform.chat.upload;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class ChatUploadManifestStore {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ChatUploadManifestStore() {
    }

    public static List<StoredUpload> list(Path chatDir) {
        Path manifestDir = manifestDir(chatDir);
        if (!Files.isDirectory(manifestDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(manifestDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(ChatUploadManifestStore::readQuietly)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public static Optional<StoredUpload> load(Path chatDir, String referenceId) {
        Path manifestFile = manifestPath(chatDir, referenceId);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        return readQuietly(manifestFile);
    }

    public static Optional<StoredUpload> findByRequestId(Path chatDir, String requestId) {
        return list(chatDir).stream()
                .filter(upload -> requestId.equals(upload.requestId()))
                .findFirst();
    }

    public static StoredUpload write(Path chatDir, StoredUpload upload) throws IOException {
        Path manifestDir = manifestDir(chatDir);
        Files.createDirectories(manifestDir);
        Path target = manifestPath(chatDir, upload.referenceId());
        Path temp = manifestDir.resolve(upload.referenceId() + ".json.tmp");
        OBJECT_MAPPER.writeValue(temp.toFile(), upload);
        moveReplacing(temp, target);
        return upload;
    }

    public static String normalizeSha256(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean matchesRequest(StoredUpload upload, String type, String name, long sizeBytes, String mimeType, String sha256) {
        if (upload == null) {
            return false;
        }
        return type.equals(upload.type())
                && name.equals(upload.name())
                && sizeBytes == upload.sizeBytes()
                && mimeType.equals(upload.mimeType())
                && java.util.Objects.equals(normalizeSha256(sha256), normalizeSha256(upload.sha256()));
    }

    public static Path resolveAssetPath(Path chatDir, StoredUpload upload) {
        return chatDir.resolve(upload.relativePath()).normalize();
    }

    private static Optional<StoredUpload> readQuietly(Path path) {
        try {
            return Optional.of(OBJECT_MAPPER.readValue(path.toFile(), StoredUpload.class));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static Path manifestDir(Path chatDir) {
        return chatDir.resolve(".uploads");
    }

    private static Path manifestPath(Path chatDir, String referenceId) {
        return manifestDir(chatDir).resolve(referenceId + ".json");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record StoredUpload(
            String requestId,
            String chatId,
            String referenceId,
            String type,
            String name,
            long sizeBytes,
            String mimeType,
            String sha256,
            String relativePath,
            String status,
            long createdAt,
            Long completedAt
    ) {
    }
}
