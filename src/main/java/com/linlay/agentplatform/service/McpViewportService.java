package com.linlay.agentplatform.service;

import com.linlay.agentplatform.model.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Service
public class McpViewportService {

    private final McpToolSyncService mcpToolSyncService;
    private final McpServerRegistryService mcpServerRegistryService;
    private final McpStreamableHttpClient mcpStreamableHttpClient;

    public McpViewportService(
            McpToolSyncService mcpToolSyncService,
            McpServerRegistryService mcpServerRegistryService,
            McpStreamableHttpClient mcpStreamableHttpClient
    ) {
        this.mcpToolSyncService = mcpToolSyncService;
        this.mcpServerRegistryService = mcpServerRegistryService;
        this.mcpStreamableHttpClient = mcpStreamableHttpClient;
    }

    public Optional<McpToolSyncService.RemoteViewportBinding> findViewport(String viewportKey) {
        return mcpToolSyncService.findViewport(viewportKey);
    }

    public Optional<String> resolveViewportType(String viewportKey) {
        return findViewport(viewportKey)
                .map(McpToolSyncService.RemoteViewportBinding::toolType)
                .filter(StringUtils::hasText);
    }

    public Optional<ResponseEntity<ApiResponse<Object>>> fetchViewport(String viewportKey) {
        Optional<McpToolSyncService.RemoteViewportBinding> bindingOptional = findViewport(viewportKey);
        if (bindingOptional.isEmpty()) {
            return Optional.empty();
        }
        McpToolSyncService.RemoteViewportBinding binding = bindingOptional.get();
        Optional<McpServerRegistryService.RegisteredServer> serverOptional = mcpServerRegistryService.find(binding.serverKey());
        if (serverOptional.isEmpty()) {
            return Optional.of(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            "MCP viewport server is not registered: " + binding.serverKey(),
                            (Object) Map.of()
                    )));
        }
        try {
            McpStreamableHttpClient.RemoteViewportResponse response = mcpStreamableHttpClient.fetchViewport(
                    serverOptional.get(),
                    viewportKey.trim()
            );
            return Optional.of(ResponseEntity.status(response.statusCode()).body(response.payload()));
        } catch (Exception ex) {
            return Optional.of(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.failure(
                            HttpStatus.BAD_GATEWAY.value(),
                            "MCP viewport request failed: " + ex.getMessage(),
                            (Object) Map.of()
                    )));
        }
    }
}
