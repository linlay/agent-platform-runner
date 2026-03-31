package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.ApiRequestLoggingWebFilter;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.LearnRequest;
import com.linlay.agentplatform.model.api.LearnResponse;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.model.api.RememberResponse;
import com.linlay.agentplatform.service.memory.GlobalMemoryRequestService;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MemoryController {

    private final GlobalMemoryRequestService globalMemoryRequestService;

    public MemoryController(GlobalMemoryRequestService globalMemoryRequestService) {
        this.globalMemoryRequestService = globalMemoryRequestService;
    }

    @PostMapping("/remember")
    public ApiResponse<RememberResponse> remember(@Valid @RequestBody RememberRequest request, ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, request.requestId());
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, bodySummary(
                request.chatId(),
                request.requestId()
        ));
        GlobalMemoryRequestService.CaptureResult result = globalMemoryRequestService.captureRemember(request);
        return ApiResponse.success(new RememberResponse(
                true,
                "captured",
                result.requestId(),
                result.chatId(),
                result.memoryPath(),
                result.detail()
        ));
    }

    @PostMapping("/learn")
    public ApiResponse<LearnResponse> learn(@Valid @RequestBody LearnRequest request, ServerWebExchange exchange) {
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_REQUEST_ID, request.requestId());
        Map<String, Object> summary = bodySummary(
                request.chatId(),
                request.requestId()
        );
        if (StringUtils.hasText(request.subjectKey())) {
            summary.put("subjectKey", request.subjectKey().trim());
        }
        exchange.getAttributes().put(ApiRequestLoggingWebFilter.ATTR_BODY_SUMMARY, summary);
        return ApiResponse.success(new LearnResponse(
                false,
                "not_connected",
                request.requestId(),
                request.chatId(),
                StringUtils.hasText(request.subjectKey()) ? request.subjectKey().trim() : null,
                "learn capability is not connected yet"
        ));
    }

    private Map<String, Object> bodySummary(String chatId, String requestId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chatId", chatId);
        summary.put("requestId", requestId);
        return summary;
    }
}
