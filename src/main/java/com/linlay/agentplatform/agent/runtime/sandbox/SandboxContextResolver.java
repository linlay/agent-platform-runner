package com.linlay.agentplatform.agent.runtime.sandbox;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.agent.runtime.sandbox.ContainerHubClient;
import com.linlay.agentplatform.agent.runtime.sandbox.SystemContainerHubBash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class SandboxContextResolver {

    private static final Logger log = LoggerFactory.getLogger(SandboxContextResolver.class);
    private static final String OPTIONAL_PROMPT_ENVIRONMENT = "shell";

    private final ContainerHubClient containerHubClient;
    private final ContainerHubToolProperties containerHubToolProperties;

    public SandboxContextResolver(
            @Nullable ContainerHubClient containerHubClient,
            ContainerHubToolProperties containerHubToolProperties
    ) {
        this.containerHubClient = containerHubClient;
        this.containerHubToolProperties = containerHubToolProperties == null ? new ContainerHubToolProperties() : containerHubToolProperties;
    }

    public RuntimeRequestContext.SandboxContext resolve(
            AgentDefinition definition,
            String chatId,
            String runId,
            String agentKey,
            String teamId,
            String chatName
    ) {
        String configuredEnvironmentId = definition != null && definition.sandboxConfig() != null
                ? normalizeNullable(definition.sandboxConfig().environmentId())
                : null;
        String defaultEnvironmentId = normalizeNullable(containerHubToolProperties.getDefaultEnvironmentId());
        String effectiveEnvironmentId = StringUtils.hasText(configuredEnvironmentId) ? configuredEnvironmentId : defaultEnvironmentId;
        String level = resolveSandboxLevel(definition);
        boolean usesContainerHubTool = definition != null
                && definition.tools() != null
                && definition.tools().stream().anyMatch(SystemContainerHubBash.TOOL_NAME::equals);
        List<String> extraMounts = summarizeExtraMounts(definition);
        ContainerHubClient.EnvironmentAgentPromptResult promptResult = fetchSandboxPrompt(
                effectiveEnvironmentId,
                runId,
                agentKey,
                chatId,
                teamId,
                chatName
        );
        return new RuntimeRequestContext.SandboxContext(
                effectiveEnvironmentId,
                configuredEnvironmentId,
                defaultEnvironmentId,
                level,
                containerHubToolProperties.isEnabled(),
                usesContainerHubTool,
                extraMounts,
                promptResult.prompt()
        );
    }

    private ContainerHubClient.EnvironmentAgentPromptResult fetchSandboxPrompt(
            String environmentId,
            String runId,
            String agentKey,
            String chatId,
            String teamId,
            String chatName
    ) {
        if (!StringUtils.hasText(environmentId)) {
            log.warn(
                    "Sandbox agent prompt resolution failed: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                    agentKey,
                    chatId,
                    runId,
                    teamId,
                    normalizeNullable(chatName),
                    environmentId,
                    "missing_environment_id"
            );
            throw new IllegalStateException("sandbox context requires a non-blank environmentId");
        }
        if (containerHubClient == null) {
            log.warn(
                    "Sandbox agent prompt fetch failed: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                    agentKey,
                    chatId,
                    runId,
                    teamId,
                    normalizeNullable(chatName),
                    environmentId,
                    "container_hub_client_unavailable"
            );
            throw new IllegalStateException("sandbox context requires container-hub client availability");
        }
        ContainerHubClient.EnvironmentAgentPromptResult result = containerHubClient.getEnvironmentAgentPrompt(environmentId);
        if (!result.ok()) {
            log.warn(
                    "Sandbox agent prompt fetch failed: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                    agentKey,
                    chatId,
                    runId,
                    teamId,
                    normalizeNullable(chatName),
                    environmentId,
                    normalizeNullable(result.error())
            );
            throw new IllegalStateException("sandbox context failed to load environment prompt for '" + environmentId + "': " + result.error());
        }
        if (!result.hasPrompt()) {
            if (isOptionalPromptEnvironment(environmentId)) {
                log.info(
                        "Sandbox agent prompt accepted without content: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                        agentKey,
                        chatId,
                        runId,
                        teamId,
                        normalizeNullable(chatName),
                        environmentId,
                        "shell_prompt_optional"
                );
                return new ContainerHubClient.EnvironmentAgentPromptResult(
                        result.environmentName(),
                        false,
                        "",
                        result.updatedAt(),
                        null
                );
            }
            log.warn(
                    "Sandbox agent prompt missing: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                    agentKey,
                    chatId,
                    runId,
                    teamId,
                    normalizeNullable(chatName),
                    environmentId,
                    "has_prompt_false"
            );
            throw new IllegalStateException("sandbox context requires a non-empty environment prompt for '" + environmentId + "'");
        }
        if (!StringUtils.hasText(result.prompt())) {
            if (isOptionalPromptEnvironment(environmentId)) {
                log.info(
                        "Sandbox agent prompt accepted without content: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                        agentKey,
                        chatId,
                        runId,
                        teamId,
                        normalizeNullable(chatName),
                        environmentId,
                        "shell_prompt_optional"
                );
                return new ContainerHubClient.EnvironmentAgentPromptResult(
                        result.environmentName(),
                        result.hasPrompt(),
                        "",
                        result.updatedAt(),
                        null
                );
            }
            log.warn(
                    "Sandbox agent prompt blank: agentKey={}, chatId={}, runId={}, teamId={}, chatName={}, environmentId={}, reason={}",
                    agentKey,
                    chatId,
                    runId,
                    teamId,
                    normalizeNullable(chatName),
                    environmentId,
                    "blank_prompt"
            );
            throw new IllegalStateException("sandbox context requires a non-blank environment prompt for '" + environmentId + "'");
        }
        return result;
    }

    private boolean isOptionalPromptEnvironment(String environmentId) {
        return OPTIONAL_PROMPT_ENVIRONMENT.equalsIgnoreCase(normalizeNullable(environmentId));
    }

    private String resolveSandboxLevel(AgentDefinition definition) {
        if (definition != null && definition.sandboxConfig() != null && definition.sandboxConfig().level() != null) {
            return definition.sandboxConfig().level().name();
        }
        String configured = normalizeNullable(containerHubToolProperties.getDefaultSandboxLevel());
        if (!StringUtils.hasText(configured)) {
            return SandboxLevel.RUN.name();
        }
        return configured.toUpperCase(Locale.ROOT);
    }

    private List<String> summarizeExtraMounts(AgentDefinition definition) {
        if (definition == null || definition.sandboxConfig() == null || definition.sandboxConfig().extraMounts() == null) {
            return List.of();
        }
        return definition.sandboxConfig().extraMounts().stream()
                .filter(mount -> mount != null)
                .map(mount -> {
                    String mode = mount.mode() == null ? "unspecified" : mount.mode().name().toLowerCase(Locale.ROOT);
                    if (StringUtils.hasText(mount.platform())) {
                        return "platform:" + mount.platform() + " (" + mode + ")";
                    }
                    if (StringUtils.hasText(mount.source()) && StringUtils.hasText(mount.destination())) {
                        return mount.source() + " -> " + mount.destination() + " (" + mode + ")";
                    }
                    if (StringUtils.hasText(mount.destination())) {
                        return "destination:" + mount.destination() + " (" + mode + ")";
                    }
                    return null;
                })
                .filter(StringUtils::hasText)
                .toList();
    }

    private String normalizeNullable(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }
}
