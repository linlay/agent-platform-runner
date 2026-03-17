package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ContainerHubRunSandboxService {

    public static final String TOOL_NAME = "container_hub_bash";
    private static final Logger log = LoggerFactory.getLogger(ContainerHubRunSandboxService.class);

    private final ContainerHubToolProperties properties;
    private final ContainerHubClient client;

    public ContainerHubRunSandboxService(ContainerHubToolProperties properties, ContainerHubClient client) {
        this.properties = properties;
        this.client = client;
    }

    public boolean requiresSandbox(AgentDefinition definition) {
        return definition != null && definition.tools().stream().anyMatch(TOOL_NAME::equals);
    }

    public void openIfNeeded(ExecutionContext context) {
        if (context == null || !requiresSandbox(context.definition())) {
            return;
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("container-hub sandbox is disabled. Configure agent.tools.container-hub.enabled=true");
        }
        String environmentId = resolveEnvironmentId(context.definition());
        if (!StringUtils.hasText(environmentId)) {
            throw new IllegalStateException("container-hub environmentId is required for agent " + context.definition().id());
        }

        ObjectNode payload = ExecutionContext.OBJECT_MAPPER.createObjectNode();
        payload.put("session_id", buildSessionId(context.request().runId()));
        payload.put("environment_name", environmentId);
        payload.set("labels", ExecutionContext.OBJECT_MAPPER.valueToTree(buildLabels(context)));

        JsonNode response = client.createSession(payload);
        if (isErrorResponse(response)) {
            throw new IllegalStateException("container-hub sandbox create failed: " + readError(response));
        }

        String sessionId = readText(response, "session_id");
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalStateException("container-hub sandbox create failed: missing session_id");
        }
        String defaultCwd = readText(response, "cwd");
        if (!StringUtils.hasText(defaultCwd)) {
            defaultCwd = "/workspace";
        }
        context.bindSandboxSession(new ExecutionContext.SandboxSession(sessionId, environmentId, defaultCwd));
    }

    public void closeQuietly(ExecutionContext context) {
        if (context == null || context.sandboxSession() == null) {
            return;
        }
        ExecutionContext.SandboxSession session = context.sandboxSession();
        try {
            JsonNode response = client.stopSession(session.sessionId());
            if (isErrorResponse(response)) {
                log.warn("container-hub sandbox stop failed for runId={}, sessionId={}, error={}",
                        context.request().runId(), session.sessionId(), readError(response));
            }
        } catch (Exception ex) {
            log.warn("container-hub sandbox stop failed for runId={}, sessionId={}",
                    context.request().runId(), session.sessionId(), ex);
        } finally {
            context.clearSandboxSession();
        }
    }

    private String resolveEnvironmentId(AgentDefinition definition) {
        if (definition != null && definition.sandboxConfig() != null
                && StringUtils.hasText(definition.sandboxConfig().environmentId())) {
            return definition.sandboxConfig().environmentId().trim();
        }
        if (StringUtils.hasText(properties.getDefaultEnvironmentId())) {
            return properties.getDefaultEnvironmentId().trim();
        }
        return null;
    }

    private Map<String, String> buildLabels(ExecutionContext context) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "agent-platform-runner");
        labels.put("chatId", context.request().chatId());
        labels.put("runId", context.request().runId());
        labels.put("agentKey", context.definition().id());
        labels.values().removeIf(value -> !StringUtils.hasText(value));
        return labels;
    }

    private String buildSessionId(String runId) {
        String normalized = StringUtils.hasText(runId) ? runId.trim().toLowerCase(Locale.ROOT) : "unknown";
        normalized = normalized.replaceAll("[^a-z0-9_.-]", "-");
        return "run-" + normalized;
    }

    private boolean isErrorResponse(JsonNode response) {
        return response != null && response.isObject() && response.path("ok").asBoolean(true) == false;
    }

    private String readError(JsonNode response) {
        String error = readText(response, "error");
        return StringUtils.hasText(error) ? error : "unknown container-hub error";
    }

    private String readText(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull() || !field.isValueNode()) {
            return null;
        }
        String value = field.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
