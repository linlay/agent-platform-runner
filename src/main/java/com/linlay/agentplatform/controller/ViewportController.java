package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.util.LoggingSanitizer;
import com.linlay.agentplatform.integration.mcp.McpViewportService;
import com.linlay.agentplatform.integration.viewport.ViewportRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ViewportController {

    private static final Logger log = LoggerFactory.getLogger(ViewportController.class);

    private final ViewportRegistryService viewportRegistryService;
    private final McpViewportService mcpViewportService;
    private final LoggingAgentProperties loggingAgentProperties;

    public ViewportController(
            ViewportRegistryService viewportRegistryService,
            McpViewportService mcpViewportService,
            LoggingAgentProperties loggingAgentProperties
    ) {
        this.viewportRegistryService = viewportRegistryService;
        this.mcpViewportService = mcpViewportService;
        this.loggingAgentProperties = loggingAgentProperties;
    }

    @GetMapping("/viewport")
    public Mono<ResponseEntity<ApiResponse<Object>>> viewport(@RequestParam String viewportKey) {
        return Mono.fromCallable(() -> resolveViewport(viewportKey))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<ApiResponse<Object>> resolveViewport(String viewportKey) {
        if (!StringUtils.hasText(viewportKey)) {
            throw new IllegalArgumentException("viewportKey is required");
        }
        return viewportRegistryService.find(viewportKey)
                .<ResponseEntity<ApiResponse<Object>>>map(viewport -> {
                    logViewport(viewportKey, HttpStatus.OK.value(), true);
                    Object data = viewport.payload();
                    if ("html".equalsIgnoreCase(viewport.viewportType().value())) {
                        data = Map.of("html", String.valueOf(viewport.payload()));
                    }
                    return ResponseEntity.ok(ApiResponse.success(data));
                })
                .orElseGet(() -> mcpViewportService.fetchViewport(viewportKey)
                        .map(response -> {
                            logViewport(viewportKey, response.getStatusCode().value(), response.getStatusCode().is2xxSuccessful());
                            return response;
                        })
                        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(notFoundViewport(viewportKey))));
    }

    private ApiResponse<Object> notFoundViewport(String viewportKey) {
        logViewport(viewportKey, HttpStatus.NOT_FOUND.value(), false);
        return ApiResponse.failure(
                HttpStatus.NOT_FOUND.value(),
                "Viewport not found: " + viewportKey,
                (Object) Map.of()
        );
    }

    private void logViewport(String viewportKey, int status, boolean hit) {
        if (loggingAgentProperties == null || !loggingAgentProperties.getViewport().isEnabled()) {
            return;
        }
        log.info(
                "api.viewport key={}, hit={}, status={}",
                LoggingSanitizer.sanitizeText(viewportKey),
                hit,
                status
        );
    }
}
