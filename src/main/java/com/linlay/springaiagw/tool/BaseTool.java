package com.linlay.springaiagw.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface BaseTool {

    String name();

    String description();

    JsonNode invoke(Map<String, Object> args);
}
