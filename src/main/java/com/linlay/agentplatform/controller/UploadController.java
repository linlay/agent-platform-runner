package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.UploadResponse;
import com.linlay.agentplatform.service.chat.ChatUploadService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ChatUploadService chatUploadService;

    public UploadController(ChatUploadService chatUploadService) {
        this.chatUploadService = chatUploadService;
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
}
