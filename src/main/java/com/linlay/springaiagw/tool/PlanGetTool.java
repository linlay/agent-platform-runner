package com.linlay.springaiagw.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PlanGetTool extends AbstractDeterministicTool {

    @Override
    public String name() {
        return "_plan_get_";
    }

    @Override
    public String description() {
        return "读取当前计划任务快照。";
    }

    @Override
    public JsonNode invoke(Map<String, Object> args) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("tool", name());
        result.put("ok", false);
        result.put("error", "Plan context is unavailable in direct invocation");
        return result;
    }
}
