package com.linlay.agentplatform.engine.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.tool.AbstractDeterministicTool;
import com.linlay.agentplatform.tool.ContextAwareTool;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

public class SystemContainerHubBash extends AbstractDeterministicTool implements ContextAwareTool {

    public static final String TOOL_NAME = "_sandbox_bash_";

    private final ContainerHubToolProperties properties;
    private final ContainerHubClient client;

    public SystemContainerHubBash(ContainerHubToolProperties properties, ContainerHubClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "在沙箱容器中执行命令。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        return failureText(TOOL_NAME + " requires an active run sandbox context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        if (context == null || context.sandboxSession() == null) {
            return failureText(TOOL_NAME + " requires an active run sandbox session");
        }
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        String command = readText(root, "command");
        if (!StringUtils.hasText(command)) {
            return failureText("Missing argument: command");
        }

        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("command", "/bin/sh");
        payload.set("args", OBJECT_MAPPER.valueToTree(List.of("-lc", command)));

        String cwd = readText(root, "cwd");
        String workingDirectory = StringUtils.hasText(cwd) ? cwd : context.sandboxSession().defaultCwd();
        if (StringUtils.hasText(workingDirectory)) {
            payload.put("cwd", workingDirectory);
        }

        Long timeoutMs = parsePositiveLong(root.get("timeout_ms"));
        if (root.has("timeout_ms") && timeoutMs == null) {
            return failureText("Invalid argument: timeout_ms must be a positive integer");
        }
        if (timeoutMs != null) {
            payload.put("timeout_ms", timeoutMs);
        }

        JsonNode response = client.executeSession(context.sandboxSession().sessionId(), payload);
        if (isErrorResponse(response)) {
            return failureText(readError(response));
        }
        if (response != null && response.isTextual()) {
            return response;
        }
        return response == null ? failureText("container-hub execute failed") : OBJECT_MAPPER.getNodeFactory().textNode(response.toString());
    }

    private JsonNode textResult(int exitCode, String stdout, String stderr, String workingDirectory) {
        String safeStdout = stdout == null ? "" : stdout;
        String safeStderr = stderr == null ? "" : stderr;
        String text = "exitCode: " + exitCode
                + "\nmode: sandbox"
                + "\n\"workingDirectory\": \"" + workingDirectory + "\""
                + "\nstdout:\n" + safeStdout
                + "\nstderr:\n" + safeStderr;
        return OBJECT_MAPPER.getNodeFactory().textNode(text);
    }

    private JsonNode failureText(String error) {
        return textResult(-1, "", error, "/workspace");
    }

    private boolean isErrorResponse(JsonNode response) {
        return response != null && response.isObject() && response.path("ok").asBoolean(true) == false;
    }

    private String readError(JsonNode response) {
        JsonNode error = response == null ? null : response.get("error");
        if (error != null && error.isValueNode() && StringUtils.hasText(error.asText())) {
            return error.asText().trim();
        }
        return "container-hub execute failed";
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long parsePositiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        long value;
        if (node.isIntegralNumber()) {
            value = node.longValue();
        } else if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                value = Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null;
        }
        return value > 0 ? value : null;
    }
}
