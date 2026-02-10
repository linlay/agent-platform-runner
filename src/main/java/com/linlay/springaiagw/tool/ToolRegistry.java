package com.linlay.springaiagw.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, BaseTool> toolsByName;

    public ToolRegistry(List<BaseTool> tools) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(BaseTool::name, Function.identity()));
    }

    public JsonNode invoke(String toolName, Map<String, Object> args) {
        BaseTool tool = toolsByName.get(toolName);
        if (Objects.isNull(tool)) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.invoke(args);
    }

    public List<BaseTool> list() {
        return List.copyOf(toolsByName.values());
    }
}
