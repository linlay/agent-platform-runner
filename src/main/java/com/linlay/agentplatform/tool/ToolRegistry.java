package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.service.McpToolSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, BaseTool> nativeToolsByName;
    private final ToolRegistryService toolRegistryService;
    private final McpToolSyncService mcpToolSyncService;
    private final Set<String> missingBackendWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> localPriorityConflictWarnings = ConcurrentHashMap.newKeySet();

    public ToolRegistry(List<BaseTool> tools) {
        this.nativeToolsByName = buildNativeToolsByName(tools);
        this.toolRegistryService = null;
        this.mcpToolSyncService = null;
    }

    @Autowired
    public ToolRegistry(
            List<BaseTool> tools,
            ObjectProvider<ToolRegistryService> toolRegistryServiceProvider,
            ObjectProvider<McpToolSyncService> mcpToolSyncServiceProvider
    ) {
        this.nativeToolsByName = buildNativeToolsByName(tools);
        this.toolRegistryService = toolRegistryServiceProvider.getIfAvailable();
        this.mcpToolSyncService = mcpToolSyncServiceProvider.getIfAvailable();
    }

    public ToolRegistry(
            List<BaseTool> tools,
            ObjectProvider<ToolRegistryService> toolRegistryServiceProvider
    ) {
        this.nativeToolsByName = buildNativeToolsByName(tools);
        this.toolRegistryService = toolRegistryServiceProvider.getIfAvailable();
        this.mcpToolSyncService = null;
    }

    public JsonNode invoke(String toolName, Map<String, Object> args) {
        BaseTool tool = nativeToolsByName.get(normalizeName(toolName));
        if (Objects.isNull(tool)) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.invoke(args);
    }

    public List<BaseTool> list() {
        Map<String, BaseTool> merged = new LinkedHashMap<>();
        nativeToolsByName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> merged.put(entry.getKey(), entry.getValue()));

        if (toolRegistryService != null) {
            for (ToolDescriptor descriptor : toolRegistryService.list()) {
                String name = normalizeName(descriptor.name());
                if (descriptor.kind() == ToolKind.BACKEND) {
                    BaseTool nativeTool = nativeToolsByName.get(name);
                    if (nativeTool == null) {
                        if (missingBackendWarnings.add(name)) {
                            log.warn("Skip backend tool '{}' because no Java tool implementation is found", name);
                        }
                        continue;
                    }
                    merged.put(name, new BackendMetadataTool(nativeTool, descriptor));
                    continue;
                }
                if (merged.containsKey(name)) {
                    continue;
                }
                merged.put(name, new VirtualTool(descriptor));
            }
        }

        if (mcpToolSyncService != null) {
            for (ToolDescriptor descriptor : mcpToolSyncService.list()) {
                String name = normalizeName(descriptor.name());
                if (merged.containsKey(name)) {
                    if (localPriorityConflictWarnings.add(name)) {
                        log.warn("MCP tool '{}' conflicts with local tool, local one keeps effective", name);
                    }
                    continue;
                }
                merged.put(name, new VirtualTool(descriptor));
            }
        }
        return List.copyOf(merged.values());
    }

    public String toolCallType(String toolName) {
        ToolDescriptor descriptor = toolDescriptor(toolName).orElse(null);
        if (descriptor == null) {
            return "function";
        }
        return switch (descriptor.kind()) {
            case FRONTEND -> normalize(descriptor.toolType(), "function");
            case ACTION -> "action";
            case BACKEND -> "function";
        };
    }

    public Optional<ToolDescriptor> toolDescriptor(String toolName) {
        String normalizedName = normalizeName(toolName);
        if (normalizedName.isBlank()) {
            return Optional.empty();
        }
        if (toolRegistryService != null) {
            Optional<ToolDescriptor> dynamic = toolRegistryService.find(normalizedName);
            if (dynamic.isPresent()) {
                return dynamic;
            }
        }
        BaseTool nativeTool = nativeToolsByName.get(normalizedName);
        if (nativeTool == null) {
            if (mcpToolSyncService == null) {
                return Optional.empty();
            }
            return mcpToolSyncService.find(normalizedName);
        }
        return Optional.of(new ToolDescriptor(
                normalizedName,
                nativeTool.description(),
                nativeTool.afterCallHint(),
                nativeTool.parametersSchema(),
                false,
                ToolKind.BACKEND,
                "function",
                null,
                "local",
                null,
                null,
                "java://builtin"
        ));
    }

    public boolean isAction(String toolName) {
        return "action".equalsIgnoreCase(toolCallType(toolName));
    }

    public boolean isFrontend(String toolName) {
        String type = toolCallType(toolName).toLowerCase(Locale.ROOT);
        return "frontend".equals(type);
    }

    public String description(String toolName) {
        return toolDescriptor(toolName).map(ToolDescriptor::description).orElse("");
    }

    private String normalizeName(String raw) {
        return normalize(raw, "").toLowerCase(Locale.ROOT);
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, BaseTool> buildNativeToolsByName(List<BaseTool> tools) {
        return tools.stream().collect(Collectors.toMap(
                tool -> normalizeName(tool.name()),
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    private static final class VirtualTool implements BaseTool {
        private final ToolDescriptor descriptor;

        private VirtualTool(ToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String name() {
            return descriptor.name();
        }

        @Override
        public String description() {
            return descriptor.description();
        }

        @Override
        public String afterCallHint() {
            return descriptor.afterCallHint();
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return descriptor.parameters();
        }

        @Override
        public JsonNode invoke(Map<String, Object> args) {
            throw new IllegalStateException("Virtual tool cannot be invoked directly: " + descriptor.name());
        }
    }

    private static final class BackendMetadataTool implements BaseTool {
        private final BaseTool delegate;
        private final ToolDescriptor descriptor;

        private BackendMetadataTool(BaseTool delegate, ToolDescriptor descriptor) {
            this.delegate = delegate;
            this.descriptor = descriptor;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return descriptor.description();
        }

        @Override
        public String afterCallHint() {
            if (descriptor.afterCallHint() != null && !descriptor.afterCallHint().isBlank()) {
                return descriptor.afterCallHint();
            }
            return delegate.afterCallHint();
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return descriptor.parameters();
        }

        @Override
        public JsonNode invoke(Map<String, Object> args) {
            return delegate.invoke(args);
        }
    }
}
