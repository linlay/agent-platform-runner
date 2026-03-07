package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.mode.ReactMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.FatalToolExecutionException;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitTimeoutException;
import com.linlay.agentplatform.agent.runtime.SkillPromptBundle;
import com.linlay.agentplatform.agent.runtime.TextBlockIdAssigner;
import com.linlay.agentplatform.agent.runtime.ToolExecutionService;
import com.linlay.agentplatform.agent.runtime.ToolInvoker;
import com.linlay.agentplatform.agent.runtime.TurnTraceWriter;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.mode.OrchestratorServices;
import com.linlay.agentplatform.config.LoggingAgentProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryStore;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.service.FrontendSubmitCoordinator;
import com.linlay.agentplatform.service.LlmService;
import com.linlay.agentplatform.service.RunIdGenerator;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
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
    private static final String FRONTEND_TIMEOUT_FALLBACK_MESSAGE = "前端工具等待用户提交超时，本次运行已结束。请重新发起或在超时前提交。";
    private static final Map<String, Object> DEFAULT_PLACEHOLDER_PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", true
    );

    private final AgentDefinition definition;
    private final ChatWindowMemoryStore chatWindowMemoryStore;
    private final ToolRegistry toolRegistry;
    private final Map<String, BaseTool> configuredToolsByName;
    private final OrchestratorServices services;
    private final ObjectMapper objectMapper;
    private final SkillRegistryService skillRegistryService;

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator
    ) {
        this(
                definition,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                null,
                new LoggingAgentProperties(),
                null
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService
    ) {
        this(
                definition,
                llmService,
                toolRegistry,
                objectMapper,
                chatWindowMemoryStore,
                frontendSubmitCoordinator,
                skillRegistryService,
                new LoggingAgentProperties(),
                null
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties
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
                null
        );
    }

    public DefinitionDrivenAgent(
            AgentDefinition definition,
            LlmService llmService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ChatWindowMemoryStore chatWindowMemoryStore,
            FrontendSubmitCoordinator frontendSubmitCoordinator,
            SkillRegistryService skillRegistryService,
            LoggingAgentProperties loggingAgentProperties,
            ToolInvoker toolInvoker
    ) {
        this.definition = definition;
        this.toolRegistry = toolRegistry;
        this.chatWindowMemoryStore = chatWindowMemoryStore;
        this.objectMapper = objectMapper;
        this.skillRegistryService = skillRegistryService;
        this.configuredToolsByName = resolveConfiguredTools(definition.tools());

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
        return definition.tools();
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
        logRunSnapshot(request);

        return Flux.defer(() -> {
                    List<Message> historyMessages = loadHistoryMessages(request.chatId());
                    ChatWindowMemoryStore.PlanSnapshot latestPlanSnapshot = loadLatestPlanSnapshot(request.chatId());
                    ChatWindowMemoryStore.SystemSnapshot latestSystem = loadLatestSystemSnapshot(request.chatId());
                    String runId = resolveRunId(request);
                    TurnTraceWriter trace = new TurnTraceWriter(
                            chatWindowMemoryStore,
                            () -> buildSystemSnapshot(request),
                            request,
                            runId,
                            latestSystem
                    );
                    SkillPromptBundle skillPromptBundle = resolveSkillPrompts();
                    ExecutionContext context = new ExecutionContext(
                            definition,
                            request,
                            historyMessages,
                            skillPromptBundle.catalogPrompt(),
                            skillPromptBundle.resolvedSkillsById()
                    );
                    if (latestPlanSnapshot != null) {
                        context.initializePlan(latestPlanSnapshot.planId, toPlanTasks(latestPlanSnapshot.tasks));
                    }
                    TextBlockIdAssigner textBlockIdAssigner = new TextBlockIdAssigner(runId);
                    return Flux.<AgentDelta>create(sink -> runWithMode(context, sink), FluxSink.OverflowStrategy.BUFFER)
                            .map(textBlockIdAssigner::assign)
                            .doOnNext(trace::capture)
                            .doOnComplete(() -> finalizeTrace(request, trace));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void runWithMode(ExecutionContext context, FluxSink<AgentDelta> sink) {
        try {
            if (context.hasPlan()) {
                services.emit(sink, AgentDelta.planUpdate(context.planId(), context.request().chatId(), context.planTasks()));
            }
            definition.agentMode().run(context, configuredToolsByName, services, sink);
            services.emit(sink, AgentDelta.finish("stop"));
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (FatalToolExecutionException ex) {
            log.info("[agent:{}] fatal tool error code={}, message={}", definition.id(), ex.code(), ex.getMessage());
            services.emit(sink, AgentDelta.content(resolveFatalToolMessage(ex)));
            services.emit(sink, AgentDelta.finish("tool_error"));
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (FrontendSubmitTimeoutException ex) {
            log.info("[agent:{}] frontend submit timeout: {}", definition.id(), ex.getMessage());
            String timeoutMessage = resolveFrontendTimeoutMessage(ex);
            services.emit(sink, AgentDelta.content(timeoutMessage));
            services.emit(sink, AgentDelta.finish("timeout"));
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (Exception ex) {
            log.warn("[agent:{}] orchestration failed", definition.id(), ex);
            services.emit(sink, AgentDelta.content("模型调用失败，请稍后重试。"));
            services.emit(sink, AgentDelta.finish("stop"));
            if (!sink.isCancelled()) {
                sink.complete();
            }
        }
    }

    private String resolveFrontendTimeoutMessage(FrontendSubmitTimeoutException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return FRONTEND_TIMEOUT_FALLBACK_MESSAGE;
        }
        String raw = ex.getMessage().trim();
        if (raw.contains("Frontend tool submit timeout")) {
            return FRONTEND_TIMEOUT_FALLBACK_MESSAGE;
        }
        return raw;
    }

    private String resolveFatalToolMessage(FatalToolExecutionException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return "工具调用失败，本次运行已结束。";
        }
        return ex.getMessage().trim();
    }

    private Map<String, BaseTool> resolveConfiguredTools(List<String> configuredTools) {
        Map<String, BaseTool> allToolsByName = new LinkedHashMap<>();
        for (BaseTool tool : toolRegistry.list()) {
            allToolsByName.put(normalizeToolName(tool.name()), tool);
        }

        List<String> requested = configuredTools == null ? List.of() : configuredTools;
        Map<String, BaseTool> resolved = new LinkedHashMap<>();
        for (String rawName : requested) {
            String name = normalizeToolName(rawName);
            if (name.isBlank()) {
                continue;
            }
            BaseTool tool = allToolsByName.get(name);
            if (tool == null) {
                log.info("[agent:{}] configured tool currently not registered, keep runtime placeholder: {}", id(), name);
                resolved.put(name, new DeclaredToolPlaceholder(name));
            } else {
                resolved.put(name, tool);
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

    private List<Message> loadHistoryMessages(String chatId) {
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

    private ChatWindowMemoryStore.PlanSnapshot loadLatestPlanSnapshot(String chatId) {
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

    private ChatWindowMemoryStore.SystemSnapshot loadLatestSystemSnapshot(String chatId) {
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

    private List<AgentDelta.PlanTask> toPlanTasks(List<ChatWindowMemoryStore.PlanTaskSnapshot> snapshotTasks) {
        if (snapshotTasks == null || snapshotTasks.isEmpty()) {
            return List.of();
        }
        List<AgentDelta.PlanTask> tasks = new ArrayList<>();
        for (ChatWindowMemoryStore.PlanTaskSnapshot task : snapshotTasks) {
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

    private ChatWindowMemoryStore.SystemSnapshot buildSystemSnapshot(AgentRequest request) {
        ChatWindowMemoryStore.SystemSnapshot snapshot = new ChatWindowMemoryStore.SystemSnapshot();
        snapshot.model = model();

        if (StringUtils.hasText(systemPrompt())) {
            ChatWindowMemoryStore.SystemMessageSnapshot systemMessage = new ChatWindowMemoryStore.SystemMessageSnapshot();
            systemMessage.role = "system";
            systemMessage.content = systemPrompt();
            snapshot.messages = List.of(systemMessage);
        }

        if (!configuredToolsByName.isEmpty()) {
            List<ChatWindowMemoryStore.SystemToolSnapshot> tools = configuredToolsByName.values().stream()
                    .sorted(java.util.Comparator.comparing(BaseTool::name))
                    .map(tool -> {
                        ChatWindowMemoryStore.SystemToolSnapshot item = new ChatWindowMemoryStore.SystemToolSnapshot();
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

    private void logRunSnapshot(AgentRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("request", requestSnapshot(request));
        snapshot.put("agent", agentSnapshot());
        snapshot.put("policy", policySnapshot());
        snapshot.put("stages", stageSnapshot());
        snapshot.put("tools", toolsSnapshot());
        snapshot.put("skills", skillsSnapshot());
        try {
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            log.info("[agent:{}] run snapshot:\n{}", id(), pretty);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to serialize run snapshot", id(), ex);
        }
    }

    private Map<String, Object> requestSnapshot(AgentRequest request) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runId", request == null ? null : request.runId());
        item.put("chatId", request == null ? null : request.chatId());
        item.put("requestId", request == null ? null : request.requestId());
        item.put("message", request == null ? null : request.message());
        return item;
    }

    private Map<String, Object> agentSnapshot() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", definition.id());
        item.put("name", definition.name());
        item.put("mode", definition.mode());
        item.put("provider", definition.providerKey());
        item.put("model", definition.model());
        item.put("skills", definition.skills());
        return item;
    }

    private Map<String, Object> policySnapshot() {
        RunSpec runSpec = definition.runSpec();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolChoice", runSpec.toolChoice());
        Budget budget = runSpec.budget();
        Map<String, Object> budgetMap = new LinkedHashMap<>();
        budgetMap.put("runTimeoutMs", budget.runTimeoutMs());

        Map<String, Object> modelBudget = new LinkedHashMap<>();
        modelBudget.put("maxCalls", budget.model().maxCalls());
        modelBudget.put("timeoutMs", budget.model().timeoutMs());
        modelBudget.put("retryCount", budget.model().retryCount());
        budgetMap.put("model", modelBudget);

        Map<String, Object> toolBudget = new LinkedHashMap<>();
        toolBudget.put("maxCalls", budget.tool().maxCalls());
        toolBudget.put("timeoutMs", budget.tool().timeoutMs());
        toolBudget.put("retryCount", budget.tool().retryCount());
        budgetMap.put("tool", toolBudget);
        item.put("budget", budgetMap);
        return item;
    }

    private Map<String, Object> stageSnapshot() {
        Map<String, Object> stages = new LinkedHashMap<>();
        AgentMode modeImpl = definition.agentMode();
        if (modeImpl instanceof OneshotMode oneshotMode) {
            stages.put("oneshot", singleStageSnapshot(oneshotMode.stage()));
            return stages;
        }
        if (modeImpl instanceof ReactMode reactMode) {
            stages.put("react", singleStageSnapshot(reactMode.stage()));
            return stages;
        }
        if (modeImpl instanceof PlanExecuteMode planExecuteMode) {
            stages.put("plan", singleStageSnapshot(planExecuteMode.planStage(), true));
            stages.put("execute", singleStageSnapshot(planExecuteMode.executeStage(), false));
            stages.put("summary", singleStageSnapshot(planExecuteMode.summaryStage(), false));
            return stages;
        }
        return stages;
    }

    private Map<String, Object> singleStageSnapshot(StageSettings stage) {
        return singleStageSnapshot(stage, true);
    }

    private Map<String, Object> singleStageSnapshot(StageSettings stage, boolean includeDeepThinking) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (stage == null) {
            return item;
        }
        item.put("provider", normalize(stage.providerKey(), definition.providerKey()));
        item.put("model", normalize(stage.model(), definition.model()));
        item.put("systemPrompt", stage.systemPrompt());
        if (includeDeepThinking) {
            item.put("deepThinking", stage.deepThinking());
        }
        item.put("reasoningEnabled", stage.reasoningEnabled());
        item.put("reasoningEffort", stage.reasoningEffort());
        item.put("tools", groupToolNames(stage.tools()));
        return item;
    }

    private Map<String, Object> toolsSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<String> registered = currentlyRegisteredConfiguredTools();
        snapshot.put("configured", groupToolNames(configuredToolsByName.keySet()));
        snapshot.put("currentlyRegistered", groupToolNames(registered));
        snapshot.put("enabled", groupToolNames(registered));

        Map<String, Object> stageTools = new LinkedHashMap<>();
        AgentMode modeImpl = definition.agentMode();
        if (modeImpl instanceof OneshotMode oneshotMode) {
            stageTools.put("oneshot", groupToolNames(oneshotMode.stage().tools()));
        } else if (modeImpl instanceof ReactMode reactMode) {
            stageTools.put("react", groupToolNames(reactMode.stage().tools()));
        } else if (modeImpl instanceof PlanExecuteMode planExecuteMode) {
            stageTools.put("plan", groupToolNames(planExecuteMode.planStage().tools()));
            stageTools.put("execute", groupToolNames(planExecuteMode.executeStage().tools()));
            stageTools.put("summary", groupToolNames(planExecuteMode.summaryStage().tools()));
        }
        snapshot.put("stageTools", stageTools);
        return snapshot;
    }

    private List<String> currentlyRegisteredConfiguredTools() {
        return configuredToolsByName.keySet().stream()
                .filter(name -> toolRegistry.descriptor(name).isPresent())
                .sorted()
                .toList();
    }

    private Map<String, Object> skillsSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("configured", definition.skills());
        if (skillRegistryService == null || definition.skills().isEmpty()) {
            snapshot.put("resolved", List.of());
            return snapshot;
        }
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (String configuredSkill : definition.skills()) {
            String skillId = normalize(configuredSkill, "").trim().toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            SkillDescriptor descriptor = skillRegistryService.find(skillId).orElse(null);
            if (descriptor == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", descriptor.id());
            item.put("name", descriptor.name());
            item.put("description", descriptor.description());
            item.put("promptTruncated", descriptor.promptTruncated());
            resolved.add(item);
        }
        snapshot.put("resolved", resolved);
        return snapshot;
    }

    private Map<String, Object> groupToolNames(Iterable<String> toolNames) {
        List<String> backend = new ArrayList<>();
        List<String> frontend = new ArrayList<>();
        List<String> action = new ArrayList<>();

        if (toolNames != null) {
            for (String raw : toolNames) {
                String name = normalizeToolName(raw);
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                switch (resolveToolGroup(name)) {
                    case "action" -> action.add(name);
                    case "frontend" -> frontend.add(name);
                    default -> backend.add(name);
                }
            }
        }

        backend = backend.stream().distinct().sorted().toList();
        frontend = frontend.stream().distinct().sorted().toList();
        action = action.stream().distinct().sorted().toList();

        Map<String, Object> grouped = new LinkedHashMap<>();
        grouped.put("backend", backend);
        grouped.put("frontend", frontend);
        grouped.put("action", action);
        return grouped;
    }

    private String resolveToolGroup(String toolName) {
        ToolDescriptor descriptor = toolRegistry.descriptor(toolName).orElse(null);
        if (descriptor != null && descriptor.kind() != null) {
            ToolKind kind = descriptor.kind();
            if (kind == ToolKind.ACTION) {
                return "action";
            }
            if (kind == ToolKind.FRONTEND) {
                return "frontend";
            }
            return "backend";
        }
        String callType = normalize(toolRegistry.toolCallType(toolName), "function").toLowerCase(Locale.ROOT);
        if ("action".equals(callType)) {
            return "action";
        }
        if ("frontend".equals(callType)) {
            return "frontend";
        }
        return "backend";
    }

    private static final class DeclaredToolPlaceholder implements BaseTool {
        private final String name;

        private DeclaredToolPlaceholder(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Configured tool placeholder. Runtime will validate registration when invoked.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return DEFAULT_PLACEHOLDER_PARAMETERS;
        }

        @Override
        public JsonNode invoke(Map<String, Object> args) {
            throw new IllegalStateException("Declared tool placeholder cannot be invoked directly: " + name);
        }
    }
}
