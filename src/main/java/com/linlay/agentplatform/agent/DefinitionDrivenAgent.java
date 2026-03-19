package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.mode.ReactMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.RunControl;
import com.linlay.agentplatform.agent.runtime.RunInputBroker;
import com.linlay.agentplatform.agent.runtime.SkillPromptBundle;
import com.linlay.agentplatform.agent.runtime.TextBlockIdAssigner;
import com.linlay.agentplatform.agent.runtime.ToolExecutionService;
import com.linlay.agentplatform.agent.runtime.ToolInvoker;
import com.linlay.agentplatform.agent.runtime.TurnTraceWriter;
import com.linlay.agentplatform.agent.mode.OrchestratorServices;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.memory.ChatMemoryTypes;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.service.RunIdGenerator;
import com.linlay.agentplatform.service.ActiveRunService;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.linlay.agentplatform.model.ChatMessage;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DefinitionDrivenAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefinitionDrivenAgent.class);

    private final AgentDefinition definition;
    private final ChatWindowMemoryStore chatWindowMemoryStore;
    private final ToolRegistry toolRegistry;
    private final Map<String, BaseTool> configuredToolsByName;
    private final List<String> effectiveToolNames;
    private final OrchestratorServices services;
    private final ObjectMapper objectMapper;
    private final SkillRegistryService skillRegistryService;
    private final ActiveRunService activeRunService;
    private final ContainerHubSandboxService containerHubSandboxService;
    private final AgentRunSnapshotLogger snapshotLogger;
    private final AgentRunLifecycle runLifecycle;

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService
    ) {
        this.definition = definition;
        this.toolRegistry = toolRegistry;
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.objectMapper = objectMapper;
        this.skillRegistryService = skillRegistryService;
        this.activeRunService = activeRunService;
        this.containerHubSandboxService = containerHubSandboxService;
        Map<String, BaseTool> resolvedTools = resolveConfiguredTools(definition.tools());
        this.configuredToolsByName = resolvedTools;
        this.effectiveToolNames = List.copyOf(resolvedTools.keySet());
        this.snapshotLogger = new AgentRunSnapshotLogger(
                log,
                objectMapper,
                definition,
                toolRegistry,
                this.configuredToolsByName,
                skillRegistryService
        );

        ToolArgumentResolver argumentResolver = new ToolArgumentResolver(objectMapper);
        ToolExecutionService toolExecutionService = new ToolExecutionService(
                toolRegistry,
                argumentResolver,
                objectMapper,
                frontendSubmitCoordinator,
                loggingAgentProperties,
                toolInvoker
        );
        this.services = new OrchestratorServices(llmService, toolExecutionService, objectMapper);
        this.runLifecycle = new AgentRunLifecycle(definition.id(), containerHubSandboxService);
    }

    @Override
    public String id() {
        return definition.id();
    }

    @Override
    public String description() {
        return definition.description();
    }

    @Override
    public String name() {
        return definition.name() == null || definition.name().isBlank() ? definition.id() : definition.name();
    }

    @Override
    public String role() {
        return definition.role() == null || definition.role().isBlank() ? name() : definition.role();
    }

    @Override
    public Object icon() {
        return definition.icon();
    }

    @Override
    public String providerKey() {
        return definition.providerKey();
    }

    @Override
    public String model() {
        return definition.model();
    }

    @Override
    public String systemPrompt() {
        return definition.systemPrompt();
    }

    @Override
    public AgentRuntimeMode mode() {
        return definition.mode();
    }

    @Override
    public List<String> tools() {
        return effectiveToolNames;
    }

    @Override
    public List<String> skills() {
        return definition.skills();
    }

    @Override
    public Flux<AgentDelta> stream(AgentRequest request) {
        log.info(
                "[agent:{}] stream start provider={}, model={}, mode={}, tools={}, skills={}, message={}",
                id(),
                providerKey(),
                model(),
                mode(),
                configuredToolsByName.keySet(),
                definition.skills(),
                normalize(request.message(), "")
        );
        snapshotLogger.logRunSnapshot(request);

        return Flux.defer(() -> {
                    List<ChatMessage> historyMessages = loadHistoryMessages(request.chatId());
                    ChatMemoryTypes.PlanSnapshot latestPlanSnapshot = loadLatestPlanSnapshot(request.chatId());
                    ChatMemoryTypes.SystemSnapshot latestSystem = loadLatestSystemSnapshot(request.chatId());
                    String runId = resolveRunId(request);
                    RunControl runControl = activeRunService == null
                            ? new RunControl()
                            : activeRunService.findControl(runId).orElseGet(RunControl::new);
                    runControl.enqueueQuery(new RunInputBroker.QueryEnvelope(request.requestId(), request.message()));
                    TurnTraceWriter trace = new TurnTraceWriter(
                            chatWindowMemoryStore,
                            () -> buildSystemSnapshot(request),
                            request,
                            runId,
                            latestSystem
                    );
                    SkillPromptBundle skillPromptBundle = resolveSkillPrompts();
                    ExecutionContext context = ExecutionContext.builder(definition, request)
                            .historyMessages(historyMessages)
                            .skillCatalogPrompt(skillPromptBundle.catalogPrompt())
                            .resolvedSkillsById(skillPromptBundle.resolvedSkillsById())
                            .runControl(runControl)
                            .build();
                    if (latestPlanSnapshot != null) {
                        context.initializePlan(latestPlanSnapshot.planId, toPlanTasks(latestPlanSnapshot.tasks));
                    }
                    TextBlockIdAssigner textBlockIdAssigner = new TextBlockIdAssigner(runId);
                    return Flux.<AgentDelta>create(
                                    sink -> runLifecycle.run(definition, context, configuredToolsByName, services, sink),
                                    FluxSink.OverflowStrategy.BUFFER
                            )
                            .map(textBlockIdAssigner::assign)
                            .doOnNext(trace::capture)
                            .doOnComplete(() -> finalizeTrace(request, trace))
                            .doFinally(signalType -> {
                                if (containerHubSandboxService != null) {
                                    containerHubSandboxService.closeQuietly(context);
                                }
                            });
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, BaseTool> resolveConfiguredTools(List<String> configuredTools) {
        List<String> requested = configuredTools == null ? List.of() : configuredTools;
        Map<String, BaseTool> resolved = new LinkedHashMap<>();
        for (String rawName : requested) {
            String name = normalizeToolName(rawName);
            if (name.isBlank()) {
                continue;
            }
            if (toolRegistry.isDisabledContainerHubTool(name)) {
                log.warn(
                        "[agent:{}] skip disabled container-hub tool because agent.tools.container-hub.enabled=false: {}",
                        id(),
                        name
                );
                continue;
            }
            ToolDescriptor descriptor = toolRegistry.descriptor(name).orElse(null);
            if (descriptor == null) {
                log.warn("[agent:{}] configured tool currently not registered, keep runtime placeholder: {}", id(), name);
                resolved.put(name, ConfiguredToolAdapters.declaredPlaceholder(name));
            } else {
                resolved.put(name, ConfiguredToolAdapters.resolvedConfiguredTool(name, descriptor));
            }
        }
        return Map.copyOf(resolved);
    }

    private String normalizeToolName(String raw) {
        return normalize(raw, "").trim().toLowerCase(Locale.ROOT);
    }

    private String sanitize(String input) {
        return normalize(input, "tool").replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private SkillPromptBundle resolveSkillPrompts() {
        if (skillRegistryService == null || definition.skills().isEmpty()) {
            return new SkillPromptBundle("", Map.of());
        }
        List<String> catalogBlocks = new ArrayList<>();
        Map<String, SkillDescriptor> resolvedSkillsById = new LinkedHashMap<>();
        for (String configuredSkill : definition.skills()) {
            String skillId = normalize(configuredSkill, "").trim().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            SkillDescriptor descriptor = skillRegistryService.find(skillId).orElse(null);
            if (descriptor == null) {
                log.warn("[agent:{}] configured skill not found and will be ignored: {}", id(), skillId);
                continue;
            }
            resolvedSkillsById.putIfAbsent(descriptor.id(), descriptor);
            StringBuilder block = new StringBuilder();
            block.append("skillId: ").append(descriptor.id());
            if (StringUtils.hasText(descriptor.name())) {
                block.append("\nname: ").append(descriptor.name());
            }
            if (StringUtils.hasText(descriptor.description())) {
                block.append("\ndescription: ").append(descriptor.description());
            }
            catalogBlocks.add(block.toString());
        }
        if (catalogBlocks.isEmpty()) {
            return new SkillPromptBundle("", Map.copyOf(resolvedSkillsById));
        }
        String catalogPrompt = definition.agentMode().skillAppend().catalogHeader() + "\n\n"
                + String.join("\n\n---\n\n", catalogBlocks);
        return new SkillPromptBundle(catalogPrompt, Map.copyOf(resolvedSkillsById));
    }

    private List<ChatMessage> loadHistoryMessages(String chatId) {
        if (chatWindowMemoryStore == null || !StringUtils.hasText(chatId)) {
            return List.of();
        }
        try {
            return chatWindowMemoryStore.loadHistoryMessages(chatId);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to load chat history chatId={}", id(), chatId, ex);
            return List.of();
        }
    }

    private ChatMemoryTypes.PlanSnapshot loadLatestPlanSnapshot(String chatId) {
        if (chatWindowMemoryStore == null || !StringUtils.hasText(chatId)) {
            return null;
        }
        try {
            return chatWindowMemoryStore.loadLatestPlanSnapshot(chatId);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to load latest plan snapshot chatId={}", id(), chatId, ex);
            return null;
        }
    }

    private ChatMemoryTypes.SystemSnapshot loadLatestSystemSnapshot(String chatId) {
        if (chatWindowMemoryStore == null || !StringUtils.hasText(chatId)) {
            return null;
        }
        try {
            return chatWindowMemoryStore.loadLatestSystemSnapshot(chatId);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to load latest system snapshot chatId={}", id(), chatId, ex);
            return null;
        }
    }

    private List<AgentDelta.PlanTask> toPlanTasks(List<ChatMemoryTypes.PlanTaskSnapshot> snapshotTasks) {
        if (snapshotTasks == null || snapshotTasks.isEmpty()) {
            return List.of();
        }
        List<AgentDelta.PlanTask> tasks = new ArrayList<>();
        for (ChatMemoryTypes.PlanTaskSnapshot task : snapshotTasks) {
            if (task == null || !StringUtils.hasText(task.taskId) || !StringUtils.hasText(task.description)) {
                continue;
            }
            tasks.add(new AgentDelta.PlanTask(task.taskId.trim(), task.description.trim(), normalizeStatus(task.status)));
        }
        return List.copyOf(tasks);
    }

    private void finalizeTrace(AgentRequest request, TurnTraceWriter trace) {
        if (chatWindowMemoryStore == null || !StringUtils.hasText(request.chatId())) {
            return;
        }
        try {
            trace.finalFlush();
            chatWindowMemoryStore.trimToWindow(request.chatId());
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to finalize chat trace chatId={}", id(), request.chatId(), ex);
        }
    }

    private String resolveRunId(AgentRequest request) {
        if (StringUtils.hasText(request.runId())) {
            return request.runId().trim();
        }
        return RunIdGenerator.nextRunId();
    }

    private ChatMemoryTypes.SystemSnapshot buildSystemSnapshot(AgentRequest request) {
        ChatMemoryTypes.SystemSnapshot snapshot = new ChatMemoryTypes.SystemSnapshot();
        snapshot.model = model();

        if (StringUtils.hasText(systemPrompt())) {
            ChatMemoryTypes.SystemMessageSnapshot systemMessage = new ChatMemoryTypes.SystemMessageSnapshot();
            systemMessage.role = "system";
            systemMessage.content = systemPrompt();
            snapshot.messages = List.of(systemMessage);
        }

        if (!configuredToolsByName.isEmpty()) {
            List<ChatMemoryTypes.SystemToolSnapshot> tools = configuredToolsByName.values().stream()
                    .sorted(java.util.Comparator.comparing(BaseTool::name))
                    .map(tool -> {
                        ChatMemoryTypes.SystemToolSnapshot item = new ChatMemoryTypes.SystemToolSnapshot();
                        item.type = toolRegistry.toolCallType(tool.name());
                        item.name = tool.name();
                        item.description = tool.description();
                        item.parameters = tool.parametersSchema();
                        return item;
                    })
                    .toList();
            if (!tools.isEmpty()) {
                snapshot.tools = tools;
            }
        }

        snapshot.stream = resolveStreamFlag(request);
        return snapshot;
    }

    private boolean resolveStreamFlag(AgentRequest request) {
        if (request == null || request.query() == null || !request.query().containsKey("stream")) {
            return true;
        }
        Object stream = request.query().get("stream");
        if (stream instanceof Boolean value) {
            return value;
        }
        if (stream instanceof Number value) {
            return value.intValue() != 0;
        }
        if (stream instanceof String value && !value.isBlank()) {
            return Boolean.parseBoolean(value.trim());
        }
        return true;
    }

    private String normalize(String value, String fallback) {
        return StringHelpers.normalize(value, fallback);
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

}
