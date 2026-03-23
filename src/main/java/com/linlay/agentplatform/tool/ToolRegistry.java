package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.service.mcp.McpToolSyncService;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    private static final String CONTAINER_HUB_TOOL_NAME = "sandbox_bash";

    private final Map<String, BaseTool> nativeToolsByName;
    private final ToolFileRegistryService toolFileRegistryService;
    private final McpToolSyncService mcpToolSyncService;
    private final ContainerHubToolProperties containerHubToolProperties;
    private final Set<String> missingBackendWarnings = ConcurrentHashMap.newKeySet();
    private final Set<String> localPriorityConflictWarnings = ConcurrentHashMap.newKeySet();

    public ToolRegistry(List<BaseTool> tools) {
        this(tools, (ToolFileRegistryService) null, (McpToolSyncService) null, defaultContainerHubProperties());
    }

    @Autowired
    public ToolRegistry(
            List<BaseTool> tools,
            ObjectProvider<ToolFileRegistryService> toolFileRegistryServiceProvider,
            ObjectProvider<McpToolSyncService> mcpToolSyncServiceProvider,
            ContainerHubToolProperties containerHubToolProperties
    ) {
        this(
                tools,
                toolFileRegistryServiceProvider.getIfAvailable(),
                mcpToolSyncServiceProvider.getIfAvailable(),
                containerHubToolProperties
        );
    }

    public ToolRegistry(
            List<BaseTool> tools,
            ObjectProvider<ToolFileRegistryService> toolFileRegistryServiceProvider
    ) {
        this(
                tools,
                toolFileRegistryServiceProvider == null ? null : toolFileRegistryServiceProvider.getIfAvailable(),
                null,
                defaultContainerHubProperties()
        );
    }

    public ToolRegistry(
            List<BaseTool> tools,
            ObjectProvider<ToolFileRegistryService> toolFileRegistryServiceProvider,
            ObjectProvider<McpToolSyncService> mcpToolSyncServiceProvider
    ) {
        this(
                tools,
                toolFileRegistryServiceProvider == null ? null : toolFileRegistryServiceProvider.getIfAvailable(),
                mcpToolSyncServiceProvider == null ? null : mcpToolSyncServiceProvider.getIfAvailable(),
                defaultContainerHubProperties()
        );
    }

    private ToolRegistry(
            List<BaseTool> tools,
            ToolFileRegistryService toolFileRegistryService,
            McpToolSyncService mcpToolSyncService,
            ContainerHubToolProperties containerHubToolProperties
    ) {
        this.nativeToolsByName = buildNativeToolsByName(tools);
        this.toolFileRegistryService = toolFileRegistryService;
        this.mcpToolSyncService = mcpToolSyncService;
        this.containerHubToolProperties = containerHubToolProperties == null
                ? defaultContainerHubProperties()
                : containerHubToolProperties;
    }

    public JsonNode invoke(String toolName, Map<String, Object> args) {
        BaseTool tool = nativeToolsByName.get(normalizeName(toolName));
        if (Objects.isNull(tool)) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.invoke(args);
    }

    public Optional<BaseTool> nativeTool(String toolName) {
        return Optional.ofNullable(nativeToolsByName.get(normalizeName(toolName)));
    }

    public List<BaseTool> list() {
        Map<String, BaseTool> merged = new LinkedHashMap<>();
        nativeToolsByName.entrySet().stream()
                .filter(entry -> isExposedTool(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> merged.put(entry.getKey(), entry.getValue()));

        if (toolFileRegistryService != null) {
            for (ToolDescriptor descriptor : toolFileRegistryService.list()) {
                String name = normalizeName(descriptor.name());
                if (!isExposedTool(name)) {
                    continue;
                }
                    if (descriptor.kind() == ToolKind.BACKEND) {
                        BaseTool nativeTool = nativeToolsByName.get(name);
                        if (nativeTool == null) {
                        if (missingBackendWarnings.add(name)) {
                            log.warn("Skip backend tool '{}' because no Java tool implementation is found", name);
                            }
                            continue;
                        }
                        merged.put(name, ToolAdapters.descriptorBacked(name, mergedBackendDescriptor(nativeTool, descriptor)));
                        continue;
                    }
                    if (merged.containsKey(name)) {
                        continue;
                    }
                    merged.put(name, ToolAdapters.descriptorBacked(name, descriptor));
                }
            }

        if (mcpToolSyncService != null) {
            for (ToolDescriptor descriptor : mcpToolSyncService.list()) {
                String name = normalizeName(descriptor.name());
                if (merged.containsKey(name)) {
                    if (localPriorityConflictWarnings.add(name)) {
                        log.warn("MCP tool '{}' conflicts with a local tool, local one keeps effective", name);
                    }
                    continue;
                }
                merged.put(name, ToolAdapters.descriptorBacked(name, descriptor));
            }
        }
        return List.copyOf(merged.values());
    }

    public String toolCallType(String toolName) {
        ToolDescriptor descriptor = descriptor(toolName).orElse(null);
        if (descriptor == null) {
            return "function";
        }
        if (descriptor.isAction()) {
            return "action";
        }
        if (descriptor.isFrontend()) {
            return normalize(descriptor.toolType(), "function");
        }
        return "function";
    }

    public Optional<ToolDescriptor> descriptor(String toolName) {
        String normalizedName = normalizeName(toolName);
        if (normalizedName.isBlank()) {
            return Optional.empty();
        }
        if (!isExposedTool(normalizedName)) {
            return Optional.empty();
        }
        BaseTool nativeTool = nativeToolsByName.get(normalizedName);
        if (toolFileRegistryService != null) {
            Optional<ToolDescriptor> dynamic = toolFileRegistryService.find(normalizedName);
            if (dynamic.isPresent()) {
                ToolDescriptor descriptor = dynamic.get();
                if (descriptor.kind() == ToolKind.BACKEND && nativeTool != null) {
                    return Optional.of(mergedBackendDescriptor(nativeTool, descriptor));
                }
                return Optional.of(descriptor);
            }
        }
        if (nativeTool == null) {
            if (mcpToolSyncService == null) {
                return Optional.empty();
            }
            return mcpToolSyncService.find(normalizedName);
        }
        return Optional.of(nativeDescriptor(nativeTool));
    }

    public boolean isAction(String toolName) {
        return descriptor(toolName).map(ToolDescriptor::isAction).orElse(false);
    }

    public boolean isFrontend(String toolName) {
        return descriptor(toolName).map(ToolDescriptor::isFrontend).orElse(false);
    }

    public boolean requiresFrontendSubmit(String toolName) {
        return descriptor(toolName)
                .map(descriptor -> descriptor.requiresFrontendSubmit()
                        || (!descriptor.hasViewport() && isFrontend(toolName)))
                .orElse(false);
    }

    public String description(String toolName) {
        return descriptor(toolName).map(ToolDescriptor::description).orElse("");
    }

    public String label(String toolName) {
        return descriptor(toolName).map(ToolDescriptor::label).orElse(null);
    }

    public boolean isDisabledContainerHubTool(String toolName) {
        return isContainerHubTool(toolName) && !containerHubToolProperties.isEnabled();
    }

    private String normalizeName(String raw) {
        return normalize(raw, "").toLowerCase(Locale.ROOT);
    }

    private String normalize(String value, String fallback) {
        return StringHelpers.trimOrDefault(value, fallback);
    }

    private boolean isExposedTool(String toolName) {
        return !isDisabledContainerHubTool(toolName);
    }

    private boolean isContainerHubTool(String toolName) {
        return CONTAINER_HUB_TOOL_NAME.equals(normalizeName(toolName));
    }

    private static ContainerHubToolProperties defaultContainerHubProperties() {
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        return properties;
    }

    private Map<String, BaseTool> buildNativeToolsByName(List<BaseTool> tools) {
        return tools.stream().collect(Collectors.toMap(
                tool -> normalizeName(tool.name()),
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    private ToolDescriptor nativeDescriptor(BaseTool tool) {
        return new ToolDescriptor(
                tool.name(),
                tool.name(),
                tool.description(),
                tool.afterCallHint(),
                tool.parametersSchema(),
                false,
                true,
                false,
                null,
                "local",
                null,
                null,
                "java://builtin"
        );
    }

    private ToolDescriptor mergedBackendDescriptor(BaseTool tool, ToolDescriptor descriptor) {
        String runtimeDescription = tool.description();
        String effectiveDescription = StringUtils.hasText(runtimeDescription)
                ? runtimeDescription
                : descriptor.description();
        String runtimeAfterCallHint = tool.afterCallHint();
        String effectiveAfterCallHint = StringUtils.hasText(descriptor.afterCallHint())
                ? descriptor.afterCallHint()
                : runtimeAfterCallHint;
        return new ToolDescriptor(
                descriptor.name(),
                descriptor.label(),
                effectiveDescription,
                effectiveAfterCallHint,
                descriptor.parameters(),
                descriptor.strict(),
                descriptor.clientVisible(),
                descriptor.toolAction(),
                descriptor.toolType(),
                descriptor.sourceType(),
                descriptor.sourceKey(),
                descriptor.viewportKey(),
                descriptor.sourceFile()
        );
    }
}
