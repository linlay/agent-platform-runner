package com.linlay.agentplatform.integration.mcp;

import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.integration.viewport.ViewportServerRegistryService;
import com.linlay.agentplatform.integration.viewport.ViewportSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

@Service
public class McpViewportService {

    private final ViewportSyncService viewportSyncService;
    private final ViewportServerRegistryService viewportServerRegistryService;
    private final McpStreamableHttpClient mcpStreamableHttpClient;

    public McpViewportService(
            ViewportSyncService viewportSyncService,
            ViewportServerRegistryService viewportServerRegistryService,
            McpStreamableHttpClient mcpStreamableHttpClient
    ) {
        this.viewportSyncService = viewportSyncService;
        this.viewportServerRegistryService = viewportServerRegistryService;
        this.mcpStreamableHttpClient = mcpStreamableHttpClient;
    }

    public Optional<ViewportSyncService.RemoteViewportBinding> findViewport(String viewportKey) {
        return viewportSyncService.findViewport(viewportKey);
    }

    public Optional<String> resolveViewportType(String viewportKey) {
        return findViewport(viewportKey)
                .map(ViewportSyncService.RemoteViewportBinding::viewportType)
                .filter(StringUtils::hasText);
    }

    public Optional<ResponseEntity<ApiResponse<Object>>> fetchViewport(String viewportKey) {
        Optional<ViewportSyncService.RemoteViewportBinding> bindingOptional = findViewport(viewportKey);
        if (bindingOptional.isEmpty()) {
            return Optional.empty();
        }
        ViewportSyncService.RemoteViewportBinding binding = bindingOptional.get();
        Optional<ViewportServerRegistryService.RegisteredServer> serverOptional = viewportServerRegistryService.find(binding.serverKey());
        if (serverOptional.isEmpty()) {
            return Optional.of(ResponseEntity.status(502)
                    .body(ApiResponse.failure(
                            502,
                            "Viewport server is not registered: " + binding.serverKey(),
                            (Object) Map.of()
                    )));
        }
        try {
            McpStreamableHttpClient.RemoteViewportPayload response = fetchRemoteViewport(serverOptional.get(), viewportKey);
            Object payload = toApiPayload(response);
            return Optional.of(ResponseEntity.ok(ApiResponse.success(payload)));
        } catch (McpStreamableHttpClient.RpcErrorException ex) {
            if (ex.error() != null && ex.error().isInvalidParams()) {
                return Optional.of(ResponseEntity.status(404)
                        .body(ApiResponse.failure(404, "Viewport not found: " + viewportKey.trim(), (Object) Map.of())));
            }
            return Optional.of(ResponseEntity.status(502)
                    .body(ApiResponse.failure(502, "MCP viewport request failed: " + ex.getMessage(), (Object) Map.of())));
        } catch (Exception ex) {
            return Optional.of(ResponseEntity.status(502)
                    .body(ApiResponse.failure(
                            502,
                            "MCP viewport request failed: " + ex.getMessage(),
                            (Object) Map.of()
                    )));
        }
    }

    private McpStreamableHttpClient.RemoteViewportPayload fetchRemoteViewport(
            ViewportServerRegistryService.RegisteredServer server,
            String viewportKey
    ) {
        String normalizedViewportKey = viewportKey == null ? "" : viewportKey.trim();
        try {
            return mcpStreamableHttpClient.getViewport(server, normalizedViewportKey);
        } catch (Exception firstFailure) {
            Optional<McpStreamableHttpClient.RemoteViewportPayload> retryResult = retryAfterRefresh(server.serverKey(), normalizedViewportKey);
            if (retryResult.isPresent()) {
                return retryResult.get();
            }
            throw firstFailure;
        }
    }

    private Optional<McpStreamableHttpClient.RemoteViewportPayload> retryAfterRefresh(
            String serverKey,
            String viewportKey
    ) {
        viewportSyncService.refreshViewportsForServers(Set.of(serverKey));
        Optional<ViewportSyncService.RemoteViewportBinding> refreshedBinding = findViewport(viewportKey);
        if (refreshedBinding.isEmpty()) {
            return Optional.empty();
        }
        Optional<ViewportServerRegistryService.RegisteredServer> refreshedServer =
                viewportServerRegistryService.find(refreshedBinding.get().serverKey());
        if (refreshedServer.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mcpStreamableHttpClient.getViewport(refreshedServer.get(), viewportKey));
    }

    private Object toApiPayload(McpStreamableHttpClient.RemoteViewportPayload response) {
        JsonNode payload = response.payload();
        if ("html".equalsIgnoreCase(response.viewportType())) {
            return Map.of("html", payload.asText(""));
        }
        return mcpStreamableHttpClient.parseJson(payload.toString());
    }
}
