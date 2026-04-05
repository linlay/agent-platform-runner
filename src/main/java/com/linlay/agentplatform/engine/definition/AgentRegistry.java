package com.linlay.agentplatform.engine.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.engine.DefinitionDrivenAgent;
import com.linlay.agentplatform.engine.prompt.RuntimeContextPromptService;
import com.linlay.agentplatform.engine.sandbox.ContainerHubSandboxService;
import com.linlay.agentplatform.engine.runtime.tool.ToolInvokerRouter;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.chat.storage.ChatStorageStore;
import com.linlay.agentplatform.engine.query.ActiveRunService;
import com.linlay.agentplatform.memory.AgentMemoryService;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.remember.GlobalMemoryRequestService;
import com.linlay.agentplatform.engine.runtime.tool.FrontendSubmitCoordinator;
import com.linlay.agentplatform.llm.LlmService;
import com.linlay.agentplatform.catalog.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AgentDefinitionLoader definitionLoader;
    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ChatStorageStore chatWindowMemoryStore;
    private final FrontendSubmitCoordinator frontendSubmitCoordinator;
    private final SkillRegistryService skillRegistryService;
    private final ToolFileRegistryService toolFileRegistryService;
    private final AgentMemoryService agentMemoryService;
    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties agentMemoryProperties;
    private final GlobalMemoryRequestService globalMemoryRequestService;
    private final AgentDefaultsProperties agentDefaultsProperties;
    private final LoggingAgentProperties loggingAgentProperties;
    private final ToolInvokerRouter toolInvokerRouter;
    private final ActiveRunService activeRunService;
    private final ContainerHubSandboxService containerHubSandboxService;
    private final RuntimeContextPromptService runtimeContextPromptService;

    private final Object reloadLock = new Object();
    private volatile Map<String, Agent> agents = Map.of();
    private volatile Map<String, AgentDefinition> definitionsById = Map.of();
    private volatile AgentDependencyIndex dependencyIndex = AgentDependencyIndex.empty();
    private volatile long selectiveReloadFallbackCount = 0;

    @Autowired
    public AgentRegistry(
            AgentDefinitionLoader definitionLoader,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            ToolFileRegistryService toolFileRegistryService,
            AgentMemoryService agentMemoryService,
            ObjectProvider<AgentMemoryStore> agentMemoryStoreProvider,
            ObjectProvider<AgentMemoryProperties> agentMemoryPropertiesProvider,
            ObjectProvider<GlobalMemoryRequestService> globalMemoryRequestServiceProvider,
            ObjectProvider<AgentDefaultsProperties> agentDefaultsPropertiesProvider,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvokerRouter toolInvokerRouter,
            ActiveRunService activeRunService,
            ObjectProvider<ContainerHubSandboxService> containerHubSandboxServiceProvider,
            ObjectProvider<RuntimeContextPromptService> runtimeContextPromptServiceProvider
    ) {
        this.definitionLoader = definitionLoader;
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.frontendSubmitCoordinator = frontendSubmitCoordinator;
        this.skillRegistryService = skillRegistryService;
        this.toolFileRegistryService = toolFileRegistryService;
        this.agentMemoryService = agentMemoryService;
        this.agentMemoryStore = agentMemoryStoreProvider.getIfAvailable();
        this.agentMemoryProperties = agentMemoryPropertiesProvider.getIfAvailable();
        this.globalMemoryRequestService = globalMemoryRequestServiceProvider.getIfAvailable();
        this.agentDefaultsProperties = agentDefaultsPropertiesProvider.getIfAvailable(AgentDefaultsProperties::new);
        this.loggingAgentProperties = loggingAgentProperties;
        this.toolInvokerRouter = toolInvokerRouter;
        this.activeRunService = activeRunService;
        this.containerHubSandboxService = containerHubSandboxServiceProvider.getIfAvailable();
        this.runtimeContextPromptService = runtimeContextPromptServiceProvider.getIfAvailable(RuntimeContextPromptService::new);
        refreshAgents();
    }

    AgentRegistry(
            AgentDefinitionLoader definitionLoader,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvokerRouter toolInvokerRouter,
            ActiveRunService activeRunService,
            ObjectProvider<ContainerHubSandboxService> containerHubSandboxServiceProvider
    ) {
        this(
                definitionLoader,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                new ToolFileRegistryService(objectMapper),
                new AgentMemoryService(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(AgentMemoryStore.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(AgentMemoryProperties.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(GlobalMemoryRequestService.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(AgentDefaultsProperties.class),
                loggingAgentProperties,
                toolInvokerRouter,
                activeRunService,
                containerHubSandboxServiceProvider,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(RuntimeContextPromptService.class)
        );
    }

    public Agent get(String id) {
        Map<String, Agent> snapshot = agents;
        Agent agent = snapshot.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("AgentKey 不存在: " + id);
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
            if (agents.isEmpty() && definitionsById.isEmpty()) {
                throw ex instanceof IllegalStateException illegalStateException
                        ? illegalStateException
                        : new IllegalStateException("Failed to initialize agents cache", ex);
            }
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
                toolFileRegistryService,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                agentMemoryService,
                agentMemoryStore,
                agentMemoryProperties,
                globalMemoryRequestService,
                loggingAgentProperties,
                toolInvokerRouter,
                activeRunService,
                containerHubSandboxService,
                runtimeContextPromptService,
                agentDefaultsProperties
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
            return StringHelpers.normalizeKey(raw);
        }
    }
}
