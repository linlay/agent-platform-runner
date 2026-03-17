package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.AgentboxToolProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "agent.tools.agentbox", name = "enabled", havingValue = "true")
public class SystemAgentboxExecute extends AbstractDeterministicTool {

    private final AgentboxToolProperties properties;
    private final AgentboxClient client;

    public SystemAgentboxExecute(AgentboxToolProperties properties, AgentboxClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String name() {
        return "agentbox_execute";
    }

    @Override
    public String description() {
        return "通过 agentbox HTTP API 创建或复用容器会话并执行命令。当前 baseUrl: " + properties.getBaseUrl();
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();

        String sessionId = readText(root, "session_id");
        boolean creatingSession = !StringUtils.hasText(sessionId);
        if (StringUtils.hasText(sessionId)) {
            payload.put("session_id", sessionId);
        }

        String command = readText(root, "command");
        if (!StringUtils.hasText(command)) {
            return failure("Missing argument: command");
        }
        payload.put("command", command);

        JsonNode argsNode = root.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                return failure("Invalid argument: args must be an array");
            }
            payload.set("args", argsNode);
        }

        String runtime = readText(root, "runtime");
        String version = readText(root, "version");
        if (creatingSession) {
            runtime = StringUtils.hasText(runtime) ? runtime : properties.getDefaultRuntime();
            version = StringUtils.hasText(version) ? version : properties.getDefaultVersion();
            if (!StringUtils.hasText(runtime)) {
                return failure("runtime is required when session_id is empty");
            }
            if (!StringUtils.hasText(version)) {
                return failure("version is required when session_id is empty");
            }
            payload.put("runtime", runtime);
            payload.put("version", version);
        } else {
            if (StringUtils.hasText(runtime)) {
                payload.put("runtime", runtime);
            }
            if (StringUtils.hasText(version)) {
                payload.put("version", version);
            }
        }

        String cwd = readText(root, "cwd");
        if (StringUtils.hasText(cwd)) {
            payload.put("cwd", cwd);
        } else if (creatingSession && StringUtils.hasText(properties.getDefaultCwd())) {
            payload.put("cwd", properties.getDefaultCwd().trim());
        }

        JsonNode envNode = root.get("env");
        if (envNode != null && !envNode.isNull()) {
            if (!envNode.isObject()) {
                return failure("Invalid argument: env must be an object");
            }
            try {
                payload.set("env", stringifyObject(envNode, "env"));
            } catch (IllegalArgumentException ex) {
                return failure(ex.getMessage());
            }
        }

        JsonNode labelsNode = root.get("labels");
        if (labelsNode != null && !labelsNode.isNull()) {
            if (!labelsNode.isObject()) {
                return failure("Invalid argument: labels must be an object");
            }
            try {
                payload.set("labels", stringifyObject(labelsNode, "labels"));
            } catch (IllegalArgumentException ex) {
                return failure(ex.getMessage());
            }
        }

        JsonNode mountsNode = root.get("mounts");
        if (mountsNode != null && !mountsNode.isNull()) {
            if (!mountsNode.isArray()) {
                return failure("Invalid argument: mounts must be an array");
            }
            payload.set("mounts", mountsNode);
        }

        JsonNode resourcesNode = root.get("resources");
        if (resourcesNode != null && !resourcesNode.isNull()) {
            if (!resourcesNode.isObject()) {
                return failure("Invalid argument: resources must be an object");
            }
            payload.set("resources", resourcesNode);
        }

        JsonNode timeoutNode = root.get("timeout_ms");
        if (timeoutNode != null && !timeoutNode.isNull()) {
            Long timeoutMs = parseLong(timeoutNode);
            if (timeoutMs == null || timeoutMs <= 0) {
                return failure("Invalid argument: timeout_ms must be a positive integer");
            }
            payload.put("timeout_ms", timeoutMs);
        }

        return client.execute(payload);
    }

    private ObjectNode stringifyObject(JsonNode node, String fieldName) {
        ObjectNode object = OBJECT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (!value.isValueNode() || value.isNull()) {
                throw new IllegalArgumentException("Invalid argument: " + fieldName + " values must be scalar");
            }
            object.put(entry.getKey(), value.asText());
        }
        return object;
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long parseLong(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private JsonNode failure(String error) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("ok", false);
        result.put("error", error);
        return result;
    }
}
