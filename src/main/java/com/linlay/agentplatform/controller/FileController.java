package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.config.properties.ChatImageTokenProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.UploadResponse;
import com.linlay.agentplatform.security.ChatImageTokenService;
import com.linlay.agentplatform.security.ChatImageTokenService.VerifyResult;
import com.linlay.agentplatform.chat.asset.ChatAssetAccessService;
import com.linlay.agentplatform.chat.asset.ChatDataPathService;
import com.linlay.agentplatform.chat.upload.ChatUploadService;
import com.linlay.agentplatform.util.LoggingSanitizer;
import com.linlay.agentplatform.util.ResourcePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final String CACHE_CONTROL_NO_STORE = "private, no-store";

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
    private final ChatImageTokenService chatImageTokenService;
    private final ChatAssetAccessService chatAssetAccessService;
    private final boolean resourceTicketEnabled;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ChatUploadService chatUploadService;

    public FileController(
            ChatDataPathService chatDataPathService,
            ChatImageTokenProperties chatImageTokenProperties,
            ChatImageTokenService chatImageTokenService,
            ChatAssetAccessService chatAssetAccessService,
            LoggingAgentProperties loggingAgentProperties,
            ChatUploadService chatUploadService
    ) {
        this.dataDir = chatDataPathService.dataDir();
        this.chatImageTokenService = chatImageTokenService;
        this.chatAssetAccessService = chatAssetAccessService;
        this.resourceTicketEnabled = chatImageTokenProperties.isResourceTicketEnabled();
        this.loggingAgentProperties = loggingAgentProperties;
        this.chatUploadService = chatUploadService;
    }

    @GetMapping("/resource")
    public Mono<ResponseEntity<?>> serveFile(
            @RequestParam("file") String file,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download,
            @RequestParam(value = "t", required = false) String resourceTicket
    ) {
        VerifyResult ticketVerifyResult = null;
        String filename = ResourcePathNormalizer.normalizeFileParam(file);
        if (filename == null) {
            return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, ApiResponse.failure(400, "Invalid file parameter")));
        }

        Path filePath = dataDir.resolve(filename).normalize();
        if (!filePath.startsWith(dataDir)) {
            return Mono.just(jsonResponse(HttpStatus.BAD_REQUEST, ApiResponse.failure(400, "Invalid filename")));
        }

        if (resourceTicketEnabled && resourceTicket != null) {
            ticketVerifyResult = chatImageTokenService.verify(resourceTicket);
            if (!ticketVerifyResult.valid()) {
                logDataForbidden("ticket_invalid", filename, ticketVerifyResult.errorCode());
                return Mono.just(forbiddenToken(ticketVerifyResult.message(), ticketVerifyResult.errorCode()));
            }
            if (!ticketVerifyResult.hasScope(ChatImageTokenService.DATA_READ_SCOPE)) {
                logDataForbidden("scope_denied", filename, ChatImageTokenService.ERROR_CODE_INVALID);
                return Mono.just(forbiddenToken("resource ticket invalid", ChatImageTokenService.ERROR_CODE_INVALID));
            }
        }

        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            return Mono.just(jsonResponse(HttpStatus.NOT_FOUND, ApiResponse.failure(404, "File not found")));
        }

        if (ticketVerifyResult != null) {
            boolean canRead = chatAssetAccessService.canRead(
                    ticketVerifyResult.claims().chatId(),
                    filename
            );
            if (!canRead) {
                logDataForbidden("asset_denied", filename, ChatImageTokenService.ERROR_CODE_INVALID);
                return Mono.just(forbiddenToken("resource ticket invalid", ChatImageTokenService.ERROR_CODE_INVALID));
            }
        }

        try {
            return Mono.just(buildFileResponse(filePath, filename, download));
        } catch (Exception e) {
            return Mono.just(jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, ApiResponse.failure(500, "Failed to read file")));
        }
    }

    @PostMapping("/upload")
    public Mono<ApiResponse<UploadResponse>> upload(
            @RequestPart("requestId") String requestId,
            @RequestPart(value = "chatId", required = false) String chatId,
            @RequestPart(value = "sha256", required = false) String sha256,
            @RequestPart("file") FilePart file,
            ServerWebExchange exchange
    ) {
        return chatUploadService.upload(requestId, chatId, sha256, file)
                .map(response -> {
                    exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, response.requestId());
                    Map<String, Object> bodySummary = new LinkedHashMap<>();
                    bodySummary.put("chatId", response.chatId());
                    bodySummary.put("name", response.upload().name());
                    bodySummary.put("sizeBytes", response.upload().sizeBytes());
                    bodySummary.put("mimeType", response.upload().mimeType());
                    bodySummary.put("type", response.upload().type());
                    if (StringUtils.hasText(sha256)) {
                        bodySummary.put("sha256", sha256.trim());
                    }
                    exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, bodySummary);
                    return ApiResponse.success(response);
                });
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

    private ResponseEntity<?> buildFileResponse(Path filePath, String filename, boolean download) throws Exception {
        Resource resource = new UrlResource(filePath.toUri());
        String contentType = guessContentType(filename);
        boolean isImage = contentType.startsWith("image/");
        String disposition;
        String downloadFilename = Path.of(filename).getFileName().toString();
        String encodedFilename = URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8).replace("+", "%20");

        if (download || !isImage) {
            disposition = "attachment; filename*=UTF-8''" + encodedFilename;
        } else {
            disposition = "inline; filename*=UTF-8''" + encodedFilename;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(resource);
    }

    private ResponseEntity<?> forbiddenToken(String message, String errorCode) {
        String resolvedMessage = StringUtils.hasText(message) ? message : "resource ticket invalid";
        String resolvedErrorCode = StringUtils.hasText(errorCode)
                ? errorCode
                : ChatImageTokenService.ERROR_CODE_INVALID;
        return jsonResponse(HttpStatus.FORBIDDEN, ApiResponse.failure(
                HttpStatus.FORBIDDEN.value(),
                resolvedMessage,
                Map.of("errorCode", resolvedErrorCode)
        ));
    }

    private ResponseEntity<?> jsonResponse(HttpStatus status, ApiResponse<?> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(body);
    }

    private void logDataForbidden(String reason, String filename, String errorCode) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getAuth().isEnabled()) {
            return;
        }
        log.warn(
                "api.resource.forbidden reason={}, file={}, errorCode={}",
                LoggingSanitizer.sanitizeText(reason),
                LoggingSanitizer.sanitizeText(filename),
                LoggingSanitizer.sanitizeText(errorCode)
        );
    }
}
