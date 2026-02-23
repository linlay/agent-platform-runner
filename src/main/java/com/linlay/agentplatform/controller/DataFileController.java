package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.DataCatalogProperties;
import com.linlay.agentplatform.model.api.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

@RestController
public class DataFileController {

    private static final Map<String, String> EXTRA_MIME_TYPES = Map.ofEntries(
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".avif", "image/avif"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".json", "application/json"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".mp3", "audio/mpeg"),
            Map.entry(".wav", "audio/wav"),
            Map.entry(".woff2", "font/woff2"),
            Map.entry(".woff", "font/woff")
    );

    private final Path dataDir;

    public DataFileController(DataCatalogProperties properties) {
        this.dataDir = Path.of(properties.getExternalDir()).toAbsolutePath().normalize();
    }

    @GetMapping("/api/ap/data")
    public Mono<ResponseEntity<?>> serveFile(
            @RequestParam("file") String file,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download
    ) {
        String filename = normalizeFileParam(file);
        if (filename == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(400, "Invalid file parameter")));
        }

        Path filePath = dataDir.resolve(filename).normalize();
        if (!filePath.startsWith(dataDir)) {
            return Mono.just(ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(400, "Invalid filename")));
        }

        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(404, "File not found")));
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            String contentType = guessContentType(filename);
            boolean isImage = contentType.startsWith("image/");
            String disposition;
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

            if (download || !isImage) {
                disposition = "attachment; filename*=UTF-8''" + encodedFilename;
            } else {
                disposition = "inline; filename*=UTF-8''" + encodedFilename;
            }

            return Mono.just(ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .body(resource));
        } catch (Exception e) {
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(500, "Failed to read file")));
        }
    }

    private String guessContentType(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = filename.substring(dotIndex).toLowerCase();
            String extra = EXTRA_MIME_TYPES.get(ext);
            if (extra != null) {
                return extra;
            }
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private String normalizeFileParam(String file) {
        if (file == null) {
            return null;
        }
        String trimmed = file.trim();
        if (trimmed.isBlank() || trimmed.contains("\\") || trimmed.contains("..")) {
            return null;
        }
        String normalized = trimmed;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return null;
        }
        try {
            Path relativePath = Path.of(normalized).normalize();
            if (relativePath.isAbsolute()) {
                return null;
            }
            return relativePath.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
