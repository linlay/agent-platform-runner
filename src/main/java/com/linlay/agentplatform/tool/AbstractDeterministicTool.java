package com.linlay.agentplatform.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

public abstract class AbstractDeterministicTool implements BaseTool {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    protected static final Clock CLOCK = Clock.systemUTC();

    protected Random randomByArgs(Map<String, Object> args) {
        TreeMap<String, Object> sorted = new TreeMap<>(args);
        String seedBase = sorted.toString();
        long seed = 0;
        for (byte b : seedBase.getBytes(StandardCharsets.UTF_8)) {
            seed = seed * 31 + b;
        }
        return new Random(seed);
    }
}
