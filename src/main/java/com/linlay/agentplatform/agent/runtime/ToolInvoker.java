package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface ToolInvoker {

    JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context);
}
