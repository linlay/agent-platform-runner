package com.linlay.agentplatform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.mode.AgentMode;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.PlanExecuteMode;
import com.linlay.agentplatform.agent.mode.ReactMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracted run snapshot diagnostics from DefinitionDrivenAgent.
 */
final class AgentRunSnapshotLogger {

    private final Logger log;
    private final ObjectMapper objectMapper;
    private final AgentDefinition definition;
    private final ToolRegistry toolRegistry;
    private final Map<String, BaseTool> configuredToolsByName;
    private final SkillRegistryService skillRegistryService;

    AgentRunSnapshotLogger(
            Logger log,
            ObjectMapper objectMapper,
            AgentDefinition definition,
            ToolRegistry toolRegistry,
            Map<String, BaseTool> configuredToolsByName,
            SkillRegistryService skillRegistryService
    ) {
        this.log = log;
        this.objectMapper = objectMapper;
        this.definition = definition;
        this.toolRegistry = toolRegistry;
        this.configuredToolsByName = configuredToolsByName;
        this.skillRegistryService = skillRegistryService;
    }

    void logRunSnapshot(AgentRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("request", requestSnapshot(request));
        snapshot.put("agent", agentSnapshot());
        snapshot.put("policy", policySnapshot());
        snapshot.put("stages", stageSnapshot());
        snapshot.put("tools", toolsSnapshot());
        snapshot.put("skills", skillsSnapshot());
        try {
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            log.info("[agent:{}] run snapshot:\n{}", definition.id(), pretty);
        } catch (Exception ex) {
            log.warn("[agent:{}] failed to serialize run snapshot", definition.id(), ex);
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
        // "enabled" currently mirrors the registered subset because runtime enablement is derived from registration.
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
        if (descriptor != null) {
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
        if (!"function".equals(callType)) {
            return "frontend";
        }
        return "backend";
    }

    private String normalizeToolName(String raw) {
        return normalize(raw, "").trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value, String fallback) {
        return StringHelpers.normalize(value, fallback);
    }
}
