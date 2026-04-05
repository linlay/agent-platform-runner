package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;

import java.util.Map;

public interface ContextAwareTool extends BaseTool {

    JsonNode invoke(Map<String, Object> args, ExecutionContext context);
}
