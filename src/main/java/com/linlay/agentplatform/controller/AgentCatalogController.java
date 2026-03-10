package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.model.api.AgentListResponse;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.SkillListResponse;
import com.linlay.agentplatform.model.api.TeamSummaryResponse;
import com.linlay.agentplatform.model.api.ToolDetailResponse;
import com.linlay.agentplatform.model.api.ToolListResponse;
import com.linlay.agentplatform.skill.SkillDescriptor;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/ap")
public class AgentCatalogController {

    private final AgentRegistry agentRegistry;
    private final TeamRegistryService teamRegistryService;
    private final SkillRegistryService skillRegistryService;
    private final ToolRegistry toolRegistry;

    public AgentCatalogController(
            AgentRegistry agentRegistry,
            TeamRegistryService teamRegistryService,
            SkillRegistryService skillRegistryService,
            ToolRegistry toolRegistry
    ) {
        this.agentRegistry = agentRegistry;
        this.teamRegistryService = teamRegistryService;
        this.skillRegistryService = skillRegistryService;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentListResponse.AgentSummary>> agents(@RequestParam(required = false) String tag) {
        List<AgentListResponse.AgentSummary> items = agentRegistry.list().stream()
                .filter(agent -> matchesTag(agent, tag))
                .map(this::toSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/teams")
    public ApiResponse<List<TeamSummaryResponse>> teams() {
        Map<String, Agent> agentsById = agentRegistry.list().stream()
                .collect(java.util.stream.Collectors.toMap(Agent::id, agent -> agent, (left, right) -> left, java.util.LinkedHashMap::new));
        List<TeamSummaryResponse> items = teamRegistryService.list().stream()
                .map(team -> toTeamSummary(team, agentsById))
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/skills")
    public ApiResponse<List<SkillListResponse.SkillSummary>> skills(@RequestParam(required = false) String tag) {
        List<SkillListResponse.SkillSummary> items = skillRegistryService.list().stream()
                .filter(skill -> matchesSkillTag(skill, tag))
                .map(this::toSkillSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/tools")
    public ApiResponse<List<ToolListResponse.ToolSummary>> tools(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String kind
    ) {
        ToolKind kindFilter = parseToolKind(kind);
        List<ToolListResponse.ToolSummary> items = toolRegistry.list().stream()
                .map(this::resolveDescriptor)
                .filter(descriptor -> matchesToolKind(descriptor, kindFilter))
                .filter(descriptor -> matchesToolTag(descriptor, tag))
                .map(this::toToolSummary)
                .toList();
        return ApiResponse.success(items);
    }

    @GetMapping("/tool")
    public ApiResponse<ToolDetailResponse> tool(@RequestParam String toolName) {
        if (!StringUtils.hasText(toolName)) {
            throw new IllegalArgumentException("toolName is required");
        }
        ToolDescriptor descriptor = toolRegistry.descriptor(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));
        return ApiResponse.success(toToolDetail(descriptor));
    }

    private boolean matchesTag(Agent agent, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        String normalized = tag.toLowerCase();
        return agent.id().toLowerCase().contains(normalized)
                || agent.description().toLowerCase().contains(normalized)
                || agent.role().toLowerCase().contains(normalized)
                || agent.tools().stream().anyMatch(tool -> tool.toLowerCase().contains(normalized))
                || agent.skills().stream().anyMatch(skill -> skill.toLowerCase().contains(normalized));
    }

    private AgentListResponse.AgentSummary toSummary(Agent agent) {
        return new AgentListResponse.AgentSummary(
                agent.id(),
                agent.name(),
                agent.icon(),
                agent.description(),
                agent.role(),
                buildMeta(agent)
        );
    }

    private TeamSummaryResponse toTeamSummary(TeamDescriptor team, Map<String, Agent> agentsById) {
        List<String> invalidAgentKeys = new java.util.ArrayList<>();
        Object icon = null;
        for (String agentKey : team.agentKeys()) {
            Agent agent = agentsById.get(agentKey);
            if (agent == null) {
                invalidAgentKeys.add(agentKey);
                continue;
            }
            if (icon == null) {
                icon = agent.icon();
            }
        }
        String defaultAgentKey = team.defaultAgentKey();
        boolean defaultAgentKeyValid = StringUtils.hasText(defaultAgentKey)
                && team.agentKeys().contains(defaultAgentKey)
                && agentsById.containsKey(defaultAgentKey);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("invalidAgentKeys", List.copyOf(invalidAgentKeys));
        meta.put("defaultAgentKey", defaultAgentKey);
        meta.put("defaultAgentKeyValid", defaultAgentKeyValid);
        return new TeamSummaryResponse(team.id(), team.name(), icon, team.agentKeys(), meta);
    }

    private boolean matchesSkillTag(SkillDescriptor skill, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return skill.id().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.name().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.description().toLowerCase(Locale.ROOT).contains(normalized)
                || skill.prompt().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private SkillListResponse.SkillSummary toSkillSummary(SkillDescriptor skill) {
        return new SkillListResponse.SkillSummary(
                skill.id(),
                skill.name(),
                skill.description(),
                Map.of("promptTruncated", skill.promptTruncated())
        );
    }

    private ToolDescriptor resolveDescriptor(BaseTool tool) {
        return toolRegistry.descriptor(tool.name()).orElseGet(() -> new ToolDescriptor(
                tool.name(),
                tool.name(),
                tool.description(),
                tool.afterCallHint(),
                tool.parametersSchema(),
                false,
                true,
                false,
                null,
                "local",
                null,
                null,
                "java://builtin"
        ));
    }

    private ToolKind parseToolKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            return null;
        }
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        try {
            return ToolKind.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid kind: " + kind + ". Use backend|frontend|action");
        }
    }

    private boolean matchesToolKind(ToolDescriptor descriptor, ToolKind kindFilter) {
        if (kindFilter == null) {
            return true;
        }
        return descriptor != null && descriptor.kind() == kindFilter;
    }

    private boolean matchesToolTag(ToolDescriptor descriptor, String tag) {
        if (!StringUtils.hasText(tag)) {
            return true;
        }
        if (descriptor == null) {
            return false;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return normalizeText(descriptor.name()).contains(normalized)
                || normalizeText(descriptor.label()).contains(normalized)
                || normalizeText(descriptor.description()).contains(normalized)
                || normalizeText(descriptor.afterCallHint()).contains(normalized)
                || normalizeText(descriptor.toolType()).contains(normalized)
                || normalizeText(descriptor.viewportKey()).contains(normalized)
                || descriptor.kind().name().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private ToolListResponse.ToolSummary toToolSummary(ToolDescriptor descriptor) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", descriptor.kind().name().toLowerCase(Locale.ROOT));
        meta.put("toolType", descriptor.toolType());
        meta.put("sourceType", descriptor.sourceType());
        meta.put("sourceKey", descriptor.sourceKey());
        meta.put("viewportKey", descriptor.viewportKey());
        meta.put("strict", descriptor.strict());
        return new ToolListResponse.ToolSummary(
                descriptor.key(),
                descriptor.name(),
                descriptor.label(),
                descriptor.description(),
                meta
        );
    }

    private ToolDetailResponse toToolDetail(ToolDescriptor descriptor) {
        return new ToolDetailResponse(
                descriptor.key(),
                descriptor.name(),
                descriptor.label(),
                descriptor.description(),
                descriptor.afterCallHint(),
                descriptor.parameters(),
                buildToolMeta(descriptor)
        );
    }

    private Map<String, Object> buildToolMeta(ToolDescriptor descriptor) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", descriptor.kind().name().toLowerCase(Locale.ROOT));
        meta.put("toolType", descriptor.toolType());
        meta.put("sourceType", descriptor.sourceType());
        meta.put("sourceKey", descriptor.sourceKey());
        meta.put("viewportKey", descriptor.viewportKey());
        meta.put("strict", descriptor.strict());
        return meta;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> buildMeta(Agent agent) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("model", agent.model());
        meta.put("mode", agent.mode().name());
        meta.put("tools", agent.tools());
        meta.put("skills", agent.skills());
        return meta;
    }
}
