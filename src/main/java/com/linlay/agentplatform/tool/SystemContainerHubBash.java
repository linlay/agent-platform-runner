package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

public class SystemContainerHubBash extends AbstractDeterministicTool implements ContextAwareTool {

    private final ContainerHubToolProperties properties;
    private final ContainerHubClient client;

    public SystemContainerHubBash(ContainerHubToolProperties properties, ContainerHubClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String name() {
        return "container_hub_bash";
    }

    @Override
    public String description() {
        return "在 container-hub 容器沙箱里执行 bash 命令。"
                + "这是一个 native HTTP bridge，会直连 agent-container-hub 的 /api/sessions/* REST 接口，"
                + "不会走 MCP transport。当前 baseUrl: " + properties.getBaseUrl();
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        return failureText("container_hub_bash requires an active run sandbox context");
    }

    @Override
    public JsonNode invoke(Map<String, Object> args, ExecutionContext context) {
        if (context == null || context.sandboxSession() == null) {
            return failureText("container_hub_bash requires an active run sandbox session");
        }
        // This tool bridges directly to agent-container-hub's REST session APIs.
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

        int exitCode = response.path("exit_code").asInt(-1);
        String stdout = response.path("stdout").asText("");
        String stderr = response.path("stderr").asText("");
        return textResult(exitCode, stdout, stderr, workingDirectory);
    }

    private JsonNode textResult(int exitCode, String stdout, String stderr, String workingDirectory) {
        String safeStdout = stdout == null ? "" : stdout;
        String safeStderr = stderr == null ? "" : stderr;
        String text = "exitCode: " + exitCode
                + "\nmode: container-hub"
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
