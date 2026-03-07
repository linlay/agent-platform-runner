package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.runtime.ToolInvokerRouter;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@DependsOn("runtimeResourceSyncService")
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AgentDefinitionLoader definitionLoader;
    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ChatWindowMemoryStore chatWindowMemoryStore;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final SkillRegistryService skillRegistryService;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ToolInvokerRouter toolInvokerRouter;

    private final Object reloadLock = new Object();
    private volatile Map<String, Agent> agents = Map.of();
    private volatile Map<String, AgentDefinition> definitionsById = Map.of();
    private volatile AgentDependencyIndex dependencyIndex = AgentDependencyIndex.empty();
    private volatile long selectiveReloadFallbackCount = 0;

    public AgentRegistry(
            AgentDefinitionLoader definitionLoader,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            ToolInvokerRouter toolInvokerRouter
    ) {
        this(
                definitionLoader,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                new LoggingAgentProperties(),
                toolInvokerRouter
        );
    }

    @Autowired
    public AgentRegistry(
            AgentDefinitionLoader definitionLoader,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvokerRouter toolInvokerRouter
    ) {
        this.definitionLoader = definitionLoader;
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
        this.skillRegistryService = skillRegistryService;
        this.loggingAgentProperties = loggingAgentProperties;
        this.toolInvokerRouter = toolInvokerRouter;
        refreshAgents();
    }

    public Agent get(String id) {
        Map<String, Agent> snapshot = agents;
        Agent agent = snapshot.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agentId: " + id + ". Available: " + snapshot.keySet().stream().sorted().toList());
        }
        return agent;
    }

    public List<String> listIds() {
        return agents.keySet().stream().sorted().toList();
    }

    public List<Agent> list() {
        return agents.values().stream()
                .sorted(Comparator.comparing(Agent::id))
                .toList();
    }

    public Agent defaultAgent() {
        List<Agent> current = list();
        if (current.isEmpty()) {
            throw new IllegalArgumentException("No agents available");
        }
        return current.getFirst();
    }

    public void refreshAgents() {
        synchronized (reloadLock) {
            refreshAllAgentsLocked("full");
        }
    }

    public void refreshAgentsByIds(Set<String> agentIds, String reason) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }
        synchronized (reloadLock) {
            long startedAt = System.nanoTime();
            Set<String> targets = normalizeAgentIds(agentIds);
            if (targets.isEmpty()) {
                return;
            }
            try {
                Map<String, AgentDefinition> latestDefinitionsById = loadDefinitionsById();
                Map<String, Agent> updatedAgents = new LinkedHashMap<>(agents);
                Map<String, AgentDefinition> updatedDefinitions = new LinkedHashMap<>(definitionsById);

                int refreshed = 0;
                int removed = 0;
                for (String agentId : targets) {
                    AgentDefinition definition = latestDefinitionsById.get(agentId);
                    if (definition == null) {
                        if (updatedAgents.remove(agentId) != null) {
                            removed++;
                        }
                        updatedDefinitions.remove(agentId);
                        continue;
                    }
                    updatedAgents.put(agentId, buildAgent(definition));
                    updatedDefinitions.put(agentId, definition);
                    refreshed++;
                }

                this.agents = Map.copyOf(updatedAgents);
                this.definitionsById = Map.copyOf(updatedDefinitions);
                this.dependencyIndex = AgentDependencyIndex.from(updatedDefinitions.values());
                long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                log.info(
                        "Refreshed selected agents, reason={}, refreshed={}, removed={}, requested={}, costMs={}, fallbackCount={}",
                        normalize(reason, "unknown"),
                        refreshed,
                        removed,
                        targets.size(),
                        costMs,
                        selectiveReloadFallbackCount
                );
            } catch (Exception ex) {
                selectiveReloadFallbackCount++;
                long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                log.warn(
                        "Failed to refresh selected agents, reason={}, ids={}, costMs={}, fallback to full refresh (count={})",
                        normalize(reason, "unknown"),
                        targets,
                        costMs,
                        selectiveReloadFallbackCount,
                        ex
                );
                refreshAllAgentsLocked("fallback:" + normalize(reason, "unknown"));
            }
        }
    }

    public Set<String> findAgentIdsByTools(Set<String> toolNames) {
        return dependencyIndex.findByTools(toolNames);
    }

    public Set<String> findAgentIdsByModels(Set<String> modelKeys) {
        return dependencyIndex.findByModels(modelKeys);
    }

    private void refreshAllAgentsLocked(String reason) {
        long startedAt = System.nanoTime();
        try {
            Map<String, AgentDefinition> latestDefinitionsById = loadDefinitionsById();
            Map<String, Agent> updatedAgents = new LinkedHashMap<>();
            for (AgentDefinition definition : latestDefinitionsById.values()) {
                Agent agent = buildAgent(definition);
                updatedAgents.put(agent.id(), agent);
            }
            this.agents = Map.copyOf(updatedAgents);
            this.definitionsById = Map.copyOf(latestDefinitionsById);
            this.dependencyIndex = AgentDependencyIndex.from(latestDefinitionsById.values());
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("Refreshed agents cache, size={}, reason={}, costMs={}", updatedAgents.size(), reason, costMs);
        } catch (Exception ex) {
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.warn("Failed to refresh agents cache, keep previous snapshot, reason={}, costMs={}", reason, costMs, ex);
        }
    }

    private Map<String, AgentDefinition> loadDefinitionsById() {
        List<AgentDefinition> definitions = definitionLoader.loadAll();
        Map<String, AgentDefinition> loaded = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            loaded.putIfAbsent(definition.id(), definition);
        }
        return loaded;
    }

    private Agent buildAgent(AgentDefinition definition) {
        return new DefinitionDrivenAgent(
                definition,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                loggingAgentProperties,
                toolInvokerRouter
        );
    }

    private Set<String> normalizeAgentIds(Set<String> agentIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String agentId : agentIds) {
            if (!StringUtils.hasText(agentId)) {
                continue;
            }
            normalized.add(agentId.trim());
        }
        return Set.copyOf(normalized);
    }

    private String normalize(String raw, String fallback) {
        return StringHelpers.trimOrDefault(raw, fallback);
    }

    private static final class AgentDependencyIndex {
        private final Map<String, Set<String>> agentIdsByTool;
        private final Map<String, Set<String>> agentIdsByModel;

        private AgentDependencyIndex(
                Map<String, Set<String>> agentIdsByTool,
                Map<String, Set<String>> agentIdsByModel
        ) {
            this.agentIdsByTool = agentIdsByTool;
            this.agentIdsByModel = agentIdsByModel;
        }

        static AgentDependencyIndex empty() {
            return new AgentDependencyIndex(Map.of(), Map.of());
        }

        static AgentDependencyIndex from(Collection<AgentDefinition> definitions) {
            Map<String, Set<String>> byTool = new LinkedHashMap<>();
            Map<String, Set<String>> byModel = new LinkedHashMap<>();
            if (definitions == null || definitions.isEmpty()) {
                return new AgentDependencyIndex(Map.of(), Map.of());
            }

            for (AgentDefinition definition : definitions) {
                if (definition == null || !StringUtils.hasText(definition.id())) {
                    continue;
                }
                String agentId = definition.id().trim();
                for (String tool : definition.tools()) {
                    String key = normalizeKey(tool);
                    if (key.isBlank()) {
                        continue;
                    }
                    byTool.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(agentId);
                }

                List<String> modelKeys = definition.modelKeys();
                if (modelKeys == null || modelKeys.isEmpty()) {
                    String key = normalizeKey(definition.modelKey());
                    if (!key.isBlank()) {
                        byModel.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(agentId);
                    }
                    continue;
                }

                for (String modelKey : modelKeys) {
                    String key = normalizeKey(modelKey);
                    if (key.isBlank()) {
                        continue;
                    }
                    byModel.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(agentId);
                }
            }

            return new AgentDependencyIndex(copyMapOfSets(byTool), copyMapOfSets(byModel));
        }

        Set<String> findByTools(Set<String> toolNames) {
            return find(agentIdsByTool, toolNames);
        }

        Set<String> findByModels(Set<String> modelKeys) {
            return find(agentIdsByModel, modelKeys);
        }

        private Set<String> find(Map<String, Set<String>> index, Set<String> keys) {
            if (index.isEmpty() || keys == null || keys.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            for (String key : keys) {
                String normalized = normalizeKey(key);
                if (normalized.isBlank()) {
                    continue;
                }
                Set<String> ids = index.get(normalized);
                if (ids != null) {
                    resolved.addAll(ids);
                }
            }
            return Set.copyOf(resolved);
        }

        private static Map<String, Set<String>> copyMapOfSets(Map<String, Set<String>> source) {
            Map<String, Set<String>> copied = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
                copied.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return Map.copyOf(copied);
        }

        private static String normalizeKey(String raw) {
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            return raw.trim().toLowerCase(Locale.ROOT);
        }
    }
}
