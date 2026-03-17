package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.config.AgentboxToolProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "agent.tools.agentbox", name = "enabled", havingValue = "true")
public class SystemAgentboxStopSession extends AbstractDeterministicTool {

    private final AgentboxToolProperties properties;
    private final AgentboxClient client;

    public SystemAgentboxStopSession(AgentboxToolProperties properties, AgentboxClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String name() {
        return "agentbox_stop_session";
    }

    @Override
    public String description() {
        return "通过 agentbox HTTP API 停止并删除会话。当前 baseUrl: " + properties.getBaseUrl();
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        JsonNode root = OBJECT_MAPPER.valueToTree(args == null ? Map.of() : args);
        JsonNode sessionNode = root.get("session_id");
        if (sessionNode == null || sessionNode.isNull() || !sessionNode.isValueNode()) {
            return failure("Missing argument: session_id");
        }
        String sessionId = sessionNode.asText();
        if (!StringUtils.hasText(sessionId)) {
            return failure("Missing argument: session_id");
        }

        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("session_id", sessionId.trim());
        return client.stopSession(payload);
    }

    private JsonNode failure(String error) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("ok", false);
        result.put("error", error);
        return result;
    }
}
