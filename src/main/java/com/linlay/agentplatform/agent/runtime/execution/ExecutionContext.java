package com.linlay.agentplatform.agent.runtime.execution;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.SkillAppend;
import com.linlay.agentplatform.agent.runtime.exception.BudgetExceededException;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.sandbox.SandboxLevel;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ChatMessage;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.util.IdGenerators;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class ExecutionContext {

    public static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final AgentDefinition definition;
    private final AgentRequest request;
    private final Budget budget;
    private final long startedAtMs;
    private final String baseSystemPrompt;
    private final String runtimeContextPrompt;
    private final String memoryPrompt;
    private final String skillCatalogPrompt;
    private final Map<String, SkillDescriptor> resolvedSkillsById;
    private final Map<String, ToolDescriptor> resolvedToolDescriptorsByName;
    private final Map<String, BaseTool> localNativeToolsByName;
    private final SkillAppend skillAppend;
    private final RunControl runControl;

    private final List<ChatMessage> conversationMessages;
    private final List<ChatMessage> planMessages;
    private final List<ChatMessage> executeMessages;
    private final List<Map<String, Object>> toolRecords = new ArrayList<>();
    private final List<AgentDelta.PlanTask> planTasks = new ArrayList<>();
    private final List<AgentDelta> deferredToolDeltas = new ArrayList<>();
    private String planId;
    private String activeTaskId;
    private SandboxSession sandboxSession;
    private Consumer<AgentDelta> deltaEmitter;
    private ToolInvocationContext activeToolInvocation;

    private int modelCalls;
    private int toolCalls;

    private ExecutionContext(Builder builder) {
        this.definition = builder.definition;
        this.request = builder.request;
        this.budget = definition.runSpec().budget();
        this.startedAtMs = System.currentTimeMillis();
        this.baseSystemPrompt = StringUtils.hasText(builder.baseSystemPrompt) ? builder.baseSystemPrompt.trim() : "";
        this.runtimeContextPrompt = StringUtils.hasText(builder.runtimeContextPrompt) ? builder.runtimeContextPrompt.trim() : "";
        this.memoryPrompt = StringUtils.hasText(builder.memoryPrompt) ? builder.memoryPrompt.trim() : "";
        this.skillCatalogPrompt = StringUtils.hasText(builder.skillCatalogPrompt) ? builder.skillCatalogPrompt.trim() : "";
        this.resolvedSkillsById = normalizeResolvedSkills(builder.resolvedSkillsById);
        this.resolvedToolDescriptorsByName = normalizeToolDescriptors(builder.resolvedToolDescriptorsByName);
        this.localNativeToolsByName = normalizeLocalTools(builder.localNativeToolsByName);
        this.skillAppend = builder.skillAppend == null ? SkillAppend.DEFAULTS : builder.skillAppend;
        this.runControl = builder.runControl == null ? new RunControl() : builder.runControl;

        this.conversationMessages = new ArrayList<>();
        if (builder.historyMessages != null) {
            this.conversationMessages.addAll(builder.historyMessages);
        }
        this.conversationMessages.add(new ChatMessage.UserMsg(request.message()));

        this.planMessages = new ArrayList<>();
        if (builder.historyMessages != null) {
            this.planMessages.addAll(builder.historyMessages);
        }
        this.planMessages.add(new ChatMessage.UserMsg(request.message()));

        this.executeMessages = new ArrayList<>();
        if (builder.historyMessages != null) {
            this.executeMessages.addAll(builder.historyMessages);
        }
        this.executeMessages.add(new ChatMessage.UserMsg(request.message()));
    }

    public static Builder builder(AgentDefinition definition, AgentRequest request) {
        return new Builder(definition, request);
    }

    public AgentDefinition definition() {
        return definition;
    }

    public AgentRequest request() {
        return request;
    }

    public String activeTaskId() {
        return activeTaskId;
    }

    public void activateTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            this.activeTaskId = null;
            return;
        }
        this.activeTaskId = taskId.trim();
    }

    public void clearActiveTask() {
        this.activeTaskId = null;
    }

    public Budget budget() {
        return budget;
    }

    public SandboxSession sandboxSession() {
        return sandboxSession;
    }

    public void bindSandboxSession(SandboxSession sandboxSession) {
        this.sandboxSession = sandboxSession;
    }

    public void clearSandboxSession() {
        this.sandboxSession = null;
    }

    public void bindDeltaEmitter(Consumer<AgentDelta> deltaEmitter) {
        this.deltaEmitter = deltaEmitter;
    }

    public void clearDeltaEmitter() {
        this.deltaEmitter = null;
    }

    public void emitDelta(AgentDelta delta) {
        Consumer<AgentDelta> emitter = this.deltaEmitter;
        if (emitter != null && delta != null) {
            emitter.accept(delta);
        }
    }

    public void deferToolDelta(AgentDelta delta) {
        if (delta != null) {
            deferredToolDeltas.add(delta);
        }
    }

    public List<AgentDelta> drainDeferredToolDeltas() {
        if (deferredToolDeltas.isEmpty()) {
            return List.of();
        }
        List<AgentDelta> drained = List.copyOf(deferredToolDeltas);
        deferredToolDeltas.clear();
        return drained;
    }

    public ToolInvocationContext activeToolInvocation() {
        return activeToolInvocation;
    }

    public void bindToolInvocation(ToolInvocationContext toolInvocationContext) {
        this.activeToolInvocation = toolInvocationContext;
    }

    public void clearToolInvocation() {
        this.activeToolInvocation = null;
    }

    public RunControl runControl() {
        return runControl;
    }

    public List<ChatMessage> conversationMessages() {
        return conversationMessages;
    }

    public List<ChatMessage> planMessages() {
        return planMessages;
    }

    public List<ChatMessage> executeMessages() {
        return executeMessages;
    }

    public void appendHumanMessageToAllContexts(String message) {
        if (!StringUtils.hasText(message)) {
            return;
        }
        ChatMessage.UserMsg userMsg = new ChatMessage.UserMsg(message.trim());
        conversationMessages.add(userMsg);
        planMessages.add(userMsg);
        executeMessages.add(userMsg);
    }

    public List<Map<String, Object>> toolRecords() {
        return toolRecords;
    }

    public List<String> skills() {
        return definition.skills();
    }

    public String skillPrompt() {
        return skillCatalogPrompt;
    }

    public String skillCatalogPrompt() {
        return skillCatalogPrompt;
    }

    public ToolDescriptor toolDescriptor(String toolName) {
        String normalized = normalizeToolName(toolName);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return resolvedToolDescriptorsByName.get(normalized);
    }

    public BaseTool localNativeTool(String toolName) {
        String normalized = normalizeToolName(toolName);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return localNativeToolsByName.get(normalized);
    }

    public String stageSystemPrompt(String instructionsPrompt, String stageSystemPrompt) {
        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(baseSystemPrompt)) {
            sections.add(baseSystemPrompt);
        }
        if (StringUtils.hasText(runtimeContextPrompt)) {
            sections.add(runtimeContextPrompt);
        }
        if (StringUtils.hasText(instructionsPrompt)) {
            sections.add(instructionsPrompt.trim());
        }
        if (StringUtils.hasText(memoryPrompt)) {
            sections.add(memoryPrompt.trim());
        }
        if (StringUtils.hasText(stageSystemPrompt)) {
            sections.add(stageSystemPrompt.trim());
        }
        if (StringUtils.hasText(skillCatalogPrompt)) {
            sections.add(skillCatalogPrompt);
        }
        return String.join("\n\n", sections);
    }

    public String planId() {
        if (StringUtils.hasText(planId)) {
            return planId;
        }
        String chatId = request.chatId();
        if (StringUtils.hasText(chatId)) {
            String stable = UUID.nameUUIDFromBytes(chatId.trim().getBytes(StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "");
            this.planId = "plan_" + stable.substring(0, 12);
            return this.planId;
        }
        String runId = request.runId();
        if (StringUtils.hasText(runId)) {
            String normalized = runId.trim().replace("-", "");
            this.planId = "plan_" + normalized.substring(0, Math.min(12, normalized.length()));
            return this.planId;
        }
        this.planId = "plan_default";
        return this.planId;
    }

    public List<AgentDelta.PlanTask> planTasks() {
        return List.copyOf(planTasks);
    }

    public void enqueueSteer(RunInputBroker.SteerEnvelope steer) {
        runControl.enqueueSteer(steer);
    }

    public List<RunInputBroker.SteerEnvelope> drainPendingSteers() {
        return runControl.drainPendingSteers();
    }

    public void interrupt() {
        runControl.interrupt();
    }

    public boolean isInterrupted() {
        return runControl.isInterrupted();
    }

    public void bindRunnerThread(Thread thread) {
        runControl.bindRunnerThread(thread);
    }

    public boolean hasPlan() {
        return !planTasks.isEmpty();
    }

    public void initializePlan(String planId, List<AgentDelta.PlanTask> tasks) {
        if (StringUtils.hasText(planId)) {
            this.planId = planId.trim();
        }
        this.planTasks.clear();
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (AgentDelta.PlanTask task : tasks) {
            if (task == null || !StringUtils.hasText(task.taskId()) || !StringUtils.hasText(task.description())) {
                continue;
            }
            this.planTasks.add(new AgentDelta.PlanTask(
                    task.taskId().trim(),
                    task.description().trim(),
                    normalizeStatus(task.status())
            ));
        }
    }

    public void appendPlanTasks(List<AgentDelta.PlanTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        Set<String> usedIds = new LinkedHashSet<>();
        for (AgentDelta.PlanTask task : planTasks) {
            if (task != null && StringUtils.hasText(task.taskId())) {
                usedIds.add(task.taskId().trim());
            }
        }
        for (AgentDelta.PlanTask task : tasks) {
            if (task == null || !StringUtils.hasText(task.description())) {
                continue;
            }
            String taskId = StringUtils.hasText(task.taskId()) ? task.taskId().trim() : "";
            if (!StringUtils.hasText(taskId) || usedIds.contains(taskId)) {
                taskId = shortId();
                while (usedIds.contains(taskId)) {
                    taskId = shortId();
                }
            }
            usedIds.add(taskId);
            planTasks.add(new AgentDelta.PlanTask(
                    taskId,
                    task.description().trim(),
                    normalizeStatus(task.status())
            ));
        }
    }

    public boolean updatePlanTask(String taskId, String status, String description) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        String normalizedTaskId = taskId.trim();
        for (int i = 0; i < planTasks.size(); i++) {
            AgentDelta.PlanTask task = planTasks.get(i);
            if (task == null || !normalizedTaskId.equals(task.taskId())) {
                continue;
            }
            String nextDescription = StringUtils.hasText(description) ? description.trim() : task.description();
            String nextStatus = normalizeStatus(status);
            planTasks.set(i, new AgentDelta.PlanTask(task.taskId(), nextDescription, nextStatus));
            return true;
        }
        return false;
    }

    public AgentDelta.PlanUpdate snapshotPlanUpdate() {
        return new AgentDelta.PlanUpdate(planId(), request.chatId(), planTasks());
    }

    public int modelCalls() {
        return modelCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public long elapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    public void incrementModelCalls() {
        this.modelCalls++;
        checkBudget();
    }

    public void incrementToolCalls(int count) {
        this.toolCalls += Math.max(0, count);
        checkBudget();
    }

    public void checkBudget() {
        if (modelCalls > budget.model().maxCalls()) {
            throw new BudgetExceededException(
                    BudgetExceededException.Kind.MODEL_CALLS,
                    "Budget exceeded: model.maxCalls=" + budget.model().maxCalls()
            );
        }
        if (toolCalls > budget.tool().maxCalls()) {
            throw new BudgetExceededException(
                    BudgetExceededException.Kind.TOOL_CALLS,
                    "Budget exceeded: tool.maxCalls=" + budget.tool().maxCalls()
            );
        }
        if (System.currentTimeMillis() - startedAtMs >= budget.runTimeoutMs()) {
            throw new BudgetExceededException(
                    BudgetExceededException.Kind.RUN_TIMEOUT,
                    "Budget exceeded: runTimeoutMs=" + budget.runTimeoutMs()
            );
        }
    }

    private String normalizeStatus(String raw) {
        return AgentDelta.normalizePlanTaskStatus(raw);
    }

    private String shortId() {
        return IdGenerators.shortHexId();
    }

    private Map<String, SkillDescriptor> normalizeResolvedSkills(Map<String, SkillDescriptor> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, SkillDescriptor> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, SkillDescriptor> entry : raw.entrySet()) {
            SkillDescriptor descriptor = entry.getValue();
            if (descriptor == null) {
                continue;
            }
            String skillId = normalizeSkillId(descriptor.id());
            if (!StringUtils.hasText(skillId)) {
                skillId = normalizeSkillId(entry.getKey());
            }
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            normalized.putIfAbsent(skillId, descriptor);
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private Map<String, ToolDescriptor> normalizeToolDescriptors(Map<String, ToolDescriptor> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, ToolDescriptor> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDescriptor> entry : raw.entrySet()) {
            ToolDescriptor descriptor = entry.getValue();
            String key = normalizeToolName(entry.getKey());
            if (!StringUtils.hasText(key) && descriptor != null) {
                key = normalizeToolName(descriptor.name());
            }
            if (!StringUtils.hasText(key) || descriptor == null) {
                continue;
            }
            normalized.putIfAbsent(key, descriptor);
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private Map<String, BaseTool> normalizeLocalTools(Map<String, BaseTool> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, BaseTool> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, BaseTool> entry : raw.entrySet()) {
            String key = normalizeToolName(entry.getKey());
            BaseTool tool = entry.getValue();
            if (!StringUtils.hasText(key) && tool != null) {
                key = normalizeToolName(tool.name());
            }
            if (!StringUtils.hasText(key) || tool == null) {
                continue;
            }
            normalized.putIfAbsent(key, tool);
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private String normalizeToolName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSkillId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public record SandboxSession(
            String sessionId,
            String environmentId,
            String defaultCwd,
            SandboxLevel level
    ) {
        public SandboxSession(String sessionId, String environmentId, String defaultCwd) {
            this(sessionId, environmentId, defaultCwd, SandboxLevel.RUN);
        }
    }

    public record ToolInvocationContext(
            String toolId,
            String toolName,
            String taskId
    ) {
    }

    public static final class Builder {
        private final AgentDefinition definition;
        private final AgentRequest request;
        private List<ChatMessage> historyMessages = List.of();
        private String baseSystemPrompt = "";
        private String runtimeContextPrompt = "";
        private String memoryPrompt = "";
        private String skillCatalogPrompt = "";
        private Map<String, SkillDescriptor> resolvedSkillsById = Map.of();
        private Map<String, ToolDescriptor> resolvedToolDescriptorsByName = Map.of();
        private Map<String, BaseTool> localNativeToolsByName = Map.of();
        private SkillAppend skillAppend;
        private RunControl runControl;

        private Builder(AgentDefinition definition, AgentRequest request) {
            this.definition = definition;
            this.request = request;
            this.skillAppend = definition == null || definition.agentMode() == null
                    ? SkillAppend.DEFAULTS
                    : definition.agentMode().skillAppend();
        }

        public Builder historyMessages(List<ChatMessage> historyMessages) {
            this.historyMessages = historyMessages == null ? List.of() : List.copyOf(historyMessages);
            return this;
        }

        public Builder baseSystemPrompt(String baseSystemPrompt) {
            this.baseSystemPrompt = baseSystemPrompt == null ? "" : baseSystemPrompt;
            return this;
        }

        public Builder runtimeContextPrompt(String runtimeContextPrompt) {
            this.runtimeContextPrompt = runtimeContextPrompt == null ? "" : runtimeContextPrompt;
            return this;
        }

        public Builder memoryPrompt(String memoryPrompt) {
            this.memoryPrompt = memoryPrompt == null ? "" : memoryPrompt;
            return this;
        }

        public Builder skillCatalogPrompt(String skillCatalogPrompt) {
            this.skillCatalogPrompt = skillCatalogPrompt == null ? "" : skillCatalogPrompt;
            return this;
        }

        public Builder resolvedSkillsById(Map<String, SkillDescriptor> resolvedSkillsById) {
            this.resolvedSkillsById = resolvedSkillsById == null ? Map.of() : Map.copyOf(resolvedSkillsById);
            return this;
        }

        public Builder resolvedToolDescriptorsByName(Map<String, ToolDescriptor> resolvedToolDescriptorsByName) {
            this.resolvedToolDescriptorsByName = resolvedToolDescriptorsByName == null ? Map.of() : Map.copyOf(resolvedToolDescriptorsByName);
            return this;
        }

        public Builder localNativeToolsByName(Map<String, BaseTool> localNativeToolsByName) {
            this.localNativeToolsByName = localNativeToolsByName == null ? Map.of() : Map.copyOf(localNativeToolsByName);
            return this;
        }

        public Builder skillAppend(SkillAppend skillAppend) {
            this.skillAppend = skillAppend;
            return this;
        }

        public Builder runControl(RunControl runControl) {
            this.runControl = runControl;
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }
}
