package com.linlay.agentplatform.agent.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.agent.runtime.execution.ExecutionContext;

import java.util.Map;

public interface ToolInvoker {

    JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context);
}
