package com.linlay.springaiagw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.service.DeltaStreamService;
import com.linlay.springaiagw.service.LlmService;
import com.linlay.springaiagw.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {

    private final AgentDefinitionLoader definitionLoader;
    private final LlmService llmService;
    private final DeltaStreamService deltaStreamService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    private final Object reloadLock = new Object();
    private volatile Map<String, Agent> agents = Map.of();

    public AgentRegistry(
            AgentDefinitionLoader definitionLoader,
            LlmService llmService,
            DeltaStreamService deltaStreamService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.definitionLoader = definitionLoader;
        this.llmService = llmService;
        this.deltaStreamService = deltaStreamService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        reloadAgents();
    }

    public Agent get(String id) {
        reloadAgents();

        Map<String, Agent> snapshot = agents;
        Agent agent = snapshot.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agentId: " + id + ". Available: " + snapshot.keySet().stream().sorted().toList());
        }
        return agent;
    }

    public List<String> listIds() {
        reloadAgents();
        return agents.keySet().stream().sorted().toList();
    }

    private void reloadAgents() {
        synchronized (reloadLock) {
            List<AgentDefinition> definitions = definitionLoader.loadAll();
            Map<String, Agent> updated = new LinkedHashMap<>();
            for (AgentDefinition definition : definitions) {
                Agent agent = new DefinitionDrivenAgent(definition, llmService, deltaStreamService, toolRegistry, objectMapper);
                updated.put(agent.id(), agent);
            }
            this.agents = Map.copyOf(updated);
        }
    }
}
