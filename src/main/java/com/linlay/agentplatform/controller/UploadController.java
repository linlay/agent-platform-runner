package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.UploadRequest;
import com.linlay.agentplatform.model.api.UploadResponse;
import com.linlay.agentplatform.service.chat.ChatUploadService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public ApiResponse<UploadResponse> reserveUpload(@Valid @RequestBody UploadRequest request, ServerWebExchange exchange) {
        UploadResponse response = chatUploadService.reserve(request);
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, response.requestId());
        Map<String, Object> bodySummary = new LinkedHashMap<>();
        bodySummary.put("chatId", response.chatId());
        bodySummary.put("type", request.type());
        bodySummary.put("name", request.name());
        bodySummary.put("sizeBytes", request.sizeBytes());
        bodySummary.put("mimeType", request.mimeType());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, bodySummary);
        return ApiResponse.success(response);
    }

    @PutMapping("/upload/{chatId}/{referenceId}")
    public Mono<ResponseEntity<Void>> uploadBinary(
            @PathVariable String chatId,
            @PathVariable String referenceId,
            @RequestBody(required = false) Mono<byte[]> body,
            ServerWebExchange exchange
    ) {
        return (body == null ? Mono.just(new byte[0]) : body.defaultIfEmpty(new byte[0]))
                .map(bytes -> {
                    chatUploadService.store(chatId, referenceId, bytes);
                    exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, Map.of(
                            "chatId", chatId,
                            "referenceId", referenceId,
                            "sizeBytes", bytes.length
                    ));
                    return ResponseEntity.noContent().build();
                });
    }
}
