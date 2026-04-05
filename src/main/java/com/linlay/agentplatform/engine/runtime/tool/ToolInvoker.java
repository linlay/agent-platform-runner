package com.linlay.agentplatform.engine.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;

import java.util.Map;

public interface ToolInvoker {

    JsonNode invoke(String toolName, Map<String, Object> args, ExecutionContext context);
}
