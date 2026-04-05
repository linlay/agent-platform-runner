package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.engine.definition.Agent;
import com.linlay.agentplatform.engine.definition.AgentDefinition;
import com.linlay.agentplatform.engine.definition.AgentRegistry;
import com.linlay.agentplatform.model.ModelProtocol;
import com.linlay.agentplatform.model.api.AgentDetailResponse;
import com.linlay.agentplatform.model.api.AgentListResponse;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.SkillListResponse;
import com.linlay.agentplatform.model.api.TeamSummaryResponse;
import com.linlay.agentplatform.model.api.ToolDetailResponse;
import com.linlay.agentplatform.model.api.ToolListResponse;
import com.linlay.agentplatform.catalog.skill.SkillDescriptor;
import com.linlay.agentplatform.catalog.skill.SkillRegistryService;
import com.linlay.agentplatform.catalog.team.TeamDescriptor;
import com.linlay.agentplatform.catalog.team.TeamRegistryService;
import com.linlay.agentplatform.tool.BaseTool;
import com.linlay.agentplatform.tool.ToolDescriptor;
import com.linlay.agentplatform.tool.ToolKind;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api")
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

    @GetMapping("/agent")
    public ApiResponse<AgentDetailResponse> agent(@RequestParam String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            throw new IllegalArgumentException("agentKey is required");
        }
        String normalizedAgentKey = agentKey.trim();
        Agent agent = agentRegistry.list().stream()
                .filter(item -> normalizedAgentKey.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + normalizedAgentKey));
        AgentDefinition definition = agent.definition().orElse(null);
        return ApiResponse.success(toDetail(agent, definition));
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
                agent.role()
        );
    }

    private AgentDetailResponse toDetail(Agent agent, AgentDefinition definition) {
        return new AgentDetailResponse(
                agent.id(),
                agent.name(),
                agent.icon(),
                agent.description(),
                agent.role(),
                agent.model(),
                agent.mode().name(),
                agent.tools(),
                agent.skills(),
                agent.controls(),
                buildDetailMeta(definition)
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

    private Map<String, Object> buildDetailMeta(AgentDefinition definition) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (definition == null) {
            return meta;
        }
        meta.put("modelKey", definition.modelKey());
        meta.put("providerKey", definition.providerKey());
        meta.put("protocol", definition.protocol() == null ? ModelProtocol.OPENAI.name() : definition.protocol().name());
        meta.put("modelKeys", definition.modelKeys());
        if (definition.sandboxConfig() != null) {
            Map<String, Object> sandbox = new java.util.LinkedHashMap<>();
            sandbox.put("environmentId", definition.sandboxConfig().environmentId());
            sandbox.put("level", definition.sandboxConfig().level() == null ? null : definition.sandboxConfig().level().name());
            if (definition.sandboxConfig().extraMounts() != null && !definition.sandboxConfig().extraMounts().isEmpty()) {
                sandbox.put("extraMounts", definition.sandboxConfig().extraMounts().stream()
                        .map(this::toSandboxExtraMountMeta)
                        .toList());
            }
            meta.put("sandbox", sandbox);
        }
        if (definition.perAgentSkills() != null && !definition.perAgentSkills().isEmpty()) {
            meta.put("perAgentSkills", definition.perAgentSkills());
        }
        return meta.isEmpty() ? Map.of() : meta;
    }

    private Map<String, Object> toSandboxExtraMountMeta(AgentDefinition.ExtraMount extraMount) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("platform", extraMount.platform());
        meta.put("source", extraMount.source());
        meta.put("destination", extraMount.destination());
        meta.put("mode", extraMount.mode() == null ? null : extraMount.mode().yamlValue());
        return meta;
    }
}
