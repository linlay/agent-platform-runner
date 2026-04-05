package com.linlay.agentplatform.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.engine.definition.Agent;
import com.linlay.agentplatform.engine.definition.AgentControl;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.mode.AgentMode;
import com.linlay.agentplatform.engine.mode.OneshotMode;
import com.linlay.agentplatform.engine.mode.PlanExecuteMode;
import com.linlay.agentplatform.engine.mode.ReactMode;
import com.linlay.agentplatform.engine.mode.StageSettings;
import com.linlay.agentplatform.engine.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.engine.runtime.AgentRunLifecycle;
import com.linlay.agentplatform.engine.runtime.AgentRunSnapshotLogger;
import com.linlay.agentplatform.engine.sandbox.ContainerHubSandboxService;
import com.linlay.agentplatform.engine.runtime.ExecutionContext;
import com.linlay.agentplatform.engine.runtime.RunControl;
import com.linlay.agentplatform.engine.runtime.RunInputBroker;
import com.linlay.agentplatform.engine.runtime.SkillPromptBundle;
import com.linlay.agentplatform.engine.runtime.TextBlockIdAssigner;
import com.linlay.agentplatform.engine.runtime.tool.ToolExecutionService;
import com.linlay.agentplatform.engine.runtime.tool.ToolInvoker;
import com.linlay.agentplatform.engine.runtime.TurnTraceWriter;
import com.linlay.agentplatform.engine.mode.OrchestratorServices;
import com.linlay.agentplatform.engine.prompt.RuntimeContextPromptService;
import com.linlay.agentplatform.config.properties.AgentDefaultsProperties;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.chat.storage.ChatStorageTypes;
import com.linlay.agentplatform.chat.storage.ChatMessage;
import com.linlay.agentplatform.chat.storage.ChatStorageStore;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.api.RememberRequest;
import com.linlay.agentplatform.engine.runtime.tool.FrontendSubmitCoordinator;
import com.linlay.agentplatform.engine.runtime.tool.ToolArgumentResolver;
import com.linlay.agentplatform.llm.LlmService;
import com.linlay.agentplatform.memory.AgentMemoryService;
import com.linlay.agentplatform.util.RunIdGenerator;
import com.linlay.agentplatform.engine.query.ActiveRunService;
import com.linlay.agentplatform.memory.store.AgentMemoryStore;
import com.linlay.agentplatform.memory.remember.GlobalMemoryRequestService;
import com.linlay.agentplatform.catalog.skill.SkillDescriptor;
import com.linlay.agentplatform.catalog.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolAdapters;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolFileRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.util.Optional;

public class DefinitionDrivenAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefinitionDrivenAgent.class);

    private final AgentDefinition definition;
    private final ChatStorageStore chatWindowMemoryStore;
    private final ToolRegistry toolRegistry;
    private final ToolFileRegistryService toolFileRegistryService;
    private final Map<String, BaseTool> configuredToolsByName;
    private final Map<String, ToolDescriptor> configuredToolDescriptorsByName;
    private final Map<String, ToolDescriptor> localToolDescriptorsByName;
    private final Map<String, BaseTool> localNativeToolsByName;
    private final List<String> effectiveToolNames;
    private final OrchestratorServices services;
    private final ObjectMapper objectMapper;
    private final SkillRegistryService skillRegistryService;
    private final AgentMemoryService agentMemoryService;
    private final AgentMemoryStore agentMemoryStore;
    private final AgentMemoryProperties agentMemoryProperties;
    private final GlobalMemoryRequestService globalMemoryRequestService;
    private final ActiveRunService activeRunService;
    private final ContainerHubSandboxService containerHubSandboxService;
    private final AgentRunSnapshotLogger snapshotLogger;
    private final AgentRunLifecycle runLifecycle;
    private final RuntimeContextPromptService runtimeContextPromptService;

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ToolFileRegistryService toolFileRegistryService,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            AgentMemoryService agentMemoryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService,
            RuntimeContextPromptService runtimeContextPromptService
    ) {
        this(
                definition,
                llmService,
                toolRegistry,
                toolFileRegistryService,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                agentMemoryService,
                null,
                null,
                null,
                loggingAgentProperties,
                toolInvoker,
                activeRunService,
                containerHubSandboxService,
                runtimeContextPromptService,
                null
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ToolFileRegistryService toolFileRegistryService,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            AgentMemoryService agentMemoryService,
            AgentMemoryStore agentMemoryStore,
            AgentMemoryProperties agentMemoryProperties,
            GlobalMemoryRequestService globalMemoryRequestService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService,
            RuntimeContextPromptService runtimeContextPromptService,
            AgentDefaultsProperties agentDefaultsProperties
    ) {
        this.definition = definition;
        this.toolRegistry = toolRegistry;
        this.toolFileRegistryService = toolFileRegistryService;
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.objectMapper = objectMapper;
        this.skillRegistryService = skillRegistryService;
        this.agentMemoryService = agentMemoryService == null ? new AgentMemoryService() : agentMemoryService;
        this.agentMemoryStore = agentMemoryStore;
        this.agentMemoryProperties = agentMemoryProperties == null ? new AgentMemoryProperties() : agentMemoryProperties;
        this.globalMemoryRequestService = globalMemoryRequestService;
        this.activeRunService = activeRunService;
        this.containerHubSandboxService = containerHubSandboxService;
        this.runtimeContextPromptService = runtimeContextPromptService;
        this.localToolDescriptorsByName = loadLocalToolDescriptors();
        ToolResolution toolResolution = resolveConfiguredTools(definition.tools());
        this.configuredToolsByName = toolResolution.tools();
        this.configuredToolDescriptorsByName = toolResolution.descriptors();
        this.localNativeToolsByName = toolResolution.localNativeTools();
        this.effectiveToolNames = List.copyOf(toolResolution.tools().keySet());
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
        this.services = new OrchestratorServices(llmService, toolExecutionService, objectMapper, agentDefaultsProperties);
        this.runLifecycle = new AgentRunLifecycle(definition.id(), containerHubSandboxService);
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ToolFileRegistryService toolFileRegistryService,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            AgentMemoryService agentMemoryService,
            AgentMemoryStore agentMemoryStore,
            AgentMemoryProperties agentMemoryProperties,
            GlobalMemoryRequestService globalMemoryRequestService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService,
            RuntimeContextPromptService runtimeContextPromptService
    ) {
        this(
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
                toolInvoker,
                activeRunService,
                containerHubSandboxService,
                runtimeContextPromptService,
                null
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService
    ) {
        this(
                definition,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                loggingAgentProperties,
                toolInvoker,
                activeRunService,
                containerHubSandboxService,
                new RuntimeContextPromptService()
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatStorageStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker,
            ActiveRunService activeRunService,
            ContainerHubSandboxService containerHubSandboxService,
            RuntimeContextPromptService runtimeContextPromptService
    ) {
        this(
                definition,
                llmService,
                toolRegistry,
                new ToolFileRegistryService(objectMapper),
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService == null ? new SkillRegistryService(new com.linlay.agentplatform.config.properties.SkillProperties()) : skillRegistryService,
                new AgentMemoryService(),
                null,
                null,
                null,
                loggingAgentProperties,
                toolInvoker,
                activeRunService,
                containerHubSandboxService,
                runtimeContextPromptService
        );
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
    public List<AgentControl> controls() {
        return definition.controls();
    }

    @Override
    public Optional<AgentDefinition> definition() {
        return Optional.of(definition);
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
                    ChatStorageTypes.PlanState latestPlanState = loadLatestPlanState(request.chatId());
                    ChatStorageTypes.SystemSnapshot latestSystem = loadLatestSystemSnapshot(request.chatId());
                    String runId = resolveRunId(request);
                    RunControl runControl = activeRunService == null
                            ? new RunControl()
                            : activeRunService.findControl(runId).orElseGet(RunControl::new);
                    runControl.enqueueQuery(new RunInputBroker.QueryEnvelope(request.requestId(), request.message()));
                    ExecutionContext[] contextHolder = new ExecutionContext[1];
                    TurnTraceWriter trace = new TurnTraceWriter(
                            chatWindowMemoryStore,
                            () -> buildSystemSnapshot(request, contextHolder[0]),
                            request,
                            runId,
                            latestSystem
                    );
                    SkillPromptBundle skillPromptBundle = resolveSkillPrompts();
                    String runtimeContextPrompt = buildRuntimeContextPrompt(request);
                    ExecutionContext context = ExecutionContext.builder(definition, request)
                            .historyMessages(historyMessages)
                            .baseSystemPrompt(buildBaseSystemPrompt())
                            .runtimeContextPrompt(runtimeContextPrompt)
                            .memoryPrompt(buildMemoryPrompt())
                            .skillCatalogPrompt(skillPromptBundle.catalogPrompt())
                            .resolvedSkillsById(skillPromptBundle.resolvedSkillsById())
                            .resolvedToolDescriptorsByName(configuredToolDescriptorsByName)
                            .localNativeToolsByName(localNativeToolsByName)
                            .runControl(runControl)
                            .build();
                    contextHolder[0] = context;
                    if (latestPlanState != null) {
                        context.initializePlan(latestPlanState.planId, toPlanTasks(latestPlanState.tasks));
                    }
                    TextBlockIdAssigner textBlockIdAssigner = new TextBlockIdAssigner(runId);
                    return Flux.<AgentDelta>create(
                                    sink -> runLifecycle.run(definition, context, configuredToolsByName, services, sink),
                                    FluxSink.OverflowStrategy.BUFFER
                            )
                            .map(textBlockIdAssigner::assign)
                            .doOnNext(trace::capture)
                            .doOnComplete(() -> finalizeRunArtifacts(request, trace, contextHolder[0]))
                            .doFinally(signalType -> {
                                if (containerHubSandboxService != null) {
                                    containerHubSandboxService.closeQuietly(context);
                                }
                            });
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResolution resolveConfiguredTools(List<String> configuredTools) {
        List<String> requested = configuredTools == null ? List.of() : configuredTools;
        Map<String, BaseTool> resolved = new LinkedHashMap<>();
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, BaseTool> localNativeTools = new LinkedHashMap<>();
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
            ToolDescriptor descriptor = resolveToolDescriptor(name);
            if (descriptor == null) {
                log.warn("[agent:{}] configured tool currently not registered, keep runtime placeholder: {}", id(), name);
                resolved.put(name, ToolAdapters.declaredPlaceholder(name));
            } else {
                resolved.put(name, ToolAdapters.descriptorBacked(name, descriptor));
                descriptors.put(name, descriptor);
            }
        }
        return new ToolResolution(
                Map.copyOf(resolved),
                descriptors.isEmpty() ? Map.of() : Map.copyOf(descriptors),
                localNativeTools.isEmpty() ? Map.of() : Map.copyOf(localNativeTools)
        );
    }

    private String normalizeToolName(String raw) {
        return normalize(raw, "").trim().toLowerCase(Locale.ROOT);
    }

    private String sanitize(String input) {
        return normalize(input, "tool").replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private SkillPromptBundle resolveSkillPrompts() {
        if (skillRegistryService == null) {
            return new SkillPromptBundle("", Map.of());
        }
        List<String> configuredSkills = new ArrayList<>();
        configuredSkills.addAll(definition.perAgentSkills());
        configuredSkills.addAll(definition.skills());
        if (configuredSkills.isEmpty()) {
            return new SkillPromptBundle("", Map.of());
        }
        List<String> catalogBlocks = new ArrayList<>();
        Map<String, SkillDescriptor> resolvedSkillsById = new LinkedHashMap<>();
        for (String configuredSkill : configuredSkills) {
            String skillId = normalize(configuredSkill, "").trim().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            SkillDescriptor descriptor = resolveSkillDescriptor(skillId);
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

    private Map<String, ToolDescriptor> loadLocalToolDescriptors() {
        if (toolFileRegistryService == null || definition.agentDir() == null) {
            return Map.of();
        }
        Path toolsDir = definition.agentDir().resolve("tools");
        return toolFileRegistryService.loadToolsDirectory(toolsDir);
    }

    private ToolDescriptor resolveToolDescriptor(String name) {
        ToolDescriptor local = localToolDescriptorsByName.get(name);
        if (local != null) {
            return local;
        }
        return toolRegistry.descriptor(name).orElse(null);
    }

    private SkillDescriptor resolveSkillDescriptor(String skillId) {
        if (definition.agentDir() != null) {
            Path perAgentSkillsDir = definition.agentDir().resolve("skills");
            Optional<SkillDescriptor> local = skillRegistryService.findForAgent(skillId, perAgentSkillsDir);
            if (local.isPresent()) {
                return local.get();
            }
        }
        return skillRegistryService.find(skillId).orElse(null);
    }

    private String buildBaseSystemPrompt() {
        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(definition.soulContent())) {
            sections.add(definition.soulContent().trim());
        }
        return String.join("\n\n", sections);
    }

    private String buildRuntimeContextPrompt(AgentRequest request) {
        if (runtimeContextPromptService == null) {
            return "";
        }
        return runtimeContextPromptService.buildPrompt(definition, request);
    }

    private String buildMemoryPrompt() {
        return "";
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

    private ChatStorageTypes.PlanState loadLatestPlanState(String chatId) {
        if (chatWindowMemoryStore == null || !StringUtils.hasText(chatId)) {
            return null;
        }
        try {
            return chatWindowMemoryStore.loadLatestPlanState(chatId);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to load latest plan snapshot chatId={}", id(), chatId, ex);
            return null;
        }
    }

    private ChatStorageTypes.SystemSnapshot loadLatestSystemSnapshot(String chatId) {
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

    private List<AgentDelta.PlanTask> toPlanTasks(List<ChatStorageTypes.PlanTaskState> snapshotTasks) {
        if (snapshotTasks == null || snapshotTasks.isEmpty()) {
            return List.of();
        }
        List<AgentDelta.PlanTask> tasks = new ArrayList<>();
        for (ChatStorageTypes.PlanTaskState task : snapshotTasks) {
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

    private void finalizeRunArtifacts(AgentRequest request, TurnTraceWriter trace, ExecutionContext context) {
        finalizeTrace(request, trace);
        persistAutomaticMemory(context);
    }

    private void persistAutomaticMemory(ExecutionContext context) {
        if (context == null
                || agentMemoryProperties == null
                || !definition.memoryEnabled()
                || context.runControl() == null
                || context.runControl().state() != com.linlay.agentplatform.engine.runtime.RunLoopState.COMPLETED) {
            return;
        }
        if (agentMemoryProperties.getAutoRemember().isEnabled()) {
            persistAutomaticRemember(context);
            return;
        }
        persistRunSummaryMemory(context);
    }

    private void persistAutomaticRemember(ExecutionContext context) {
        if (globalMemoryRequestService == null || context.request() == null || !StringUtils.hasText(context.request().chatId())) {
            return;
        }
        try {
            globalMemoryRequestService.captureRemember(new RememberRequest(
                    context.request().requestId(),
                    context.request().chatId()
            )).block();
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to persist auto-remember memories", id(), ex);
        }
    }

    private void persistRunSummaryMemory(ExecutionContext context) {
        if (agentMemoryStore == null) {
            return;
        }
        String content = buildAutomaticMemoryContent(context);
        if (!StringUtils.hasText(content)) {
            return;
        }
        try {
            agentMemoryStore.write(new AgentMemoryStore.WriteRequest(
                    definition.id(),
                    context.request() == null ? null : context.request().requestId(),
                    context.request() == null ? null : context.request().chatId(),
                    null,
                    content,
                    "run-summary",
                    "run-summary",
                    5,
                    List.of("auto", "run-summary")
            ));
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to persist automatic memory", id(), ex);
        }
    }

    private String buildAutomaticMemoryContent(ExecutionContext context) {
        List<String> sections = new ArrayList<>();
        String requestText = normalize(context.request() == null ? null : context.request().message(), "");
        if (StringUtils.hasText(requestText)) {
            sections.add("User Request:\n" + requestText.trim());
        }
        String finalAnswer = latestAssistantText(context.conversationMessages());
        if (StringUtils.hasText(finalAnswer)) {
            sections.add("Final Response:\n" + finalAnswer.trim());
        }
        List<String> toolSummaries = summarizeToolRecords(context.toolRecords());
        if (!toolSummaries.isEmpty()) {
            sections.add("Tools Used:\n" + String.join("\n", toolSummaries));
        }
        return String.join("\n\n", sections).trim();
    }

    private String latestAssistantText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ChatMessage.AssistantMsg assistant && StringUtils.hasText(assistant.text())) {
                return assistant.text().trim();
            }
        }
        return "";
    }

    private List<String> summarizeToolRecords(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int limit = Math.min(records.size(), 8);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> record = records.get(i);
            Object rawToolName = record == null ? null : record.get("toolName");
            String toolName = normalize(rawToolName == null ? null : rawToolName.toString(), "tool");
            String resultPreview = previewToolResult(record == null ? null : record.get("result"));
            lines.add("- " + toolName + ": " + resultPreview);
        }
        if (records.size() > limit) {
            lines.add("- +" + (records.size() - limit) + " more tool call(s)");
        }
        return List.copyOf(lines);
    }

    private String previewToolResult(Object value) {
        if (value == null) {
            return "completed";
        }
        try {
            String raw = value instanceof String text ? text : objectMapper.writeValueAsString(value);
            String compact = raw.replaceAll("\\s+", " ").trim();
            if (!StringUtils.hasText(compact)) {
                return "completed";
            }
            return compact.length() > 160 ? compact.substring(0, 157) + "..." : compact;
        } catch (Exception ex) {
            return "completed";
        }
    }

    private String resolveRunId(AgentRequest request) {
        if (StringUtils.hasText(request.runId())) {
            return request.runId().trim();
        }
        return RunIdGenerator.nextRunId();
    }

    private ChatStorageTypes.SystemSnapshot buildSystemSnapshot(AgentRequest request, ExecutionContext context) {
        ChatStorageTypes.SystemSnapshot snapshot = new ChatStorageTypes.SystemSnapshot();
        snapshot.model = model();

        String snapshotPrompt = buildSnapshotSystemPrompt(context);
        if (StringUtils.hasText(snapshotPrompt)) {
            ChatStorageTypes.SystemMessageSnapshot systemMessage = new ChatStorageTypes.SystemMessageSnapshot();
            systemMessage.role = "system";
            systemMessage.content = snapshotPrompt;
            snapshot.messages = List.of(systemMessage);
        }

        if (!configuredToolsByName.isEmpty()) {
            List<ChatStorageTypes.SystemToolSnapshot> tools = configuredToolsByName.values().stream()
                    .sorted(java.util.Comparator.comparing(BaseTool::name))
                    .map(tool -> {
                        ChatStorageTypes.SystemToolSnapshot item = new ChatStorageTypes.SystemToolSnapshot();
                        ToolDescriptor descriptor = resolveToolDescriptor(tool.name());
                        item.type = descriptor == null
                                ? toolRegistry.toolCallType(tool.name())
                                : (descriptor.isAction()
                                        ? "action"
                                        : (descriptor.isFrontend() && StringUtils.hasText(descriptor.toolType())
                                                ? descriptor.toolType()
                                                : "function"));
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

    private String buildSnapshotSystemPrompt(ExecutionContext context) {
        if (context == null) {
            return systemPrompt();
        }
        return context.stageSystemPrompt(primaryInstructionsPrompt(), systemPrompt());
    }

    private String primaryInstructionsPrompt() {
        AgentMode agentMode = definition.agentMode();
        if (agentMode instanceof OneshotMode oneshotMode) {
            return oneshotMode.stage().instructionsPrompt();
        }
        if (agentMode instanceof ReactMode reactMode) {
            return reactMode.stage().instructionsPrompt();
        }
        if (agentMode instanceof PlanExecuteMode planExecuteMode) {
            return planExecuteMode.planStage().instructionsPrompt();
        }
        return null;
    }

    private String normalize(String value, String fallback) {
        return StringHelpers.normalize(value, fallback);
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private record ToolResolution(
            Map<String, BaseTool> tools,
            Map<String, ToolDescriptor> descriptors,
            Map<String, BaseTool> localNativeTools
    ) {
    }

}
