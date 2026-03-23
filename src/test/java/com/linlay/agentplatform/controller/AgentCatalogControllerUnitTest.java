package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.agent.Agent;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.AgentRegistry;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.MountAccessMode;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.model.api.AgentDetailResponse;
import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.skill.SkillRegistryService;
import com.linlay.agentplatform.team.TeamRegistryService;
import com.linlay.agentplatform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCatalogControllerUnitTest {

    @Test
    void agentDetailShouldExposeSandboxExtraMountMode() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);

        AgentDefinition definition = new AgentDefinition(
                "sandboxed",
                "sandboxed",
                null,
                "demo",
                "role",
                null,
                "bailian",
                "qwen3-max",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.AUTO, Budget.DEFAULT),
                null,
                List.of("sandbox_bash"),
                List.of(),
                List.of(),
                new AgentDefinition.SandboxConfig(
                        "shell",
                        SandboxLevel.RUN,
                        List.of(
                                new AgentDefinition.ExtraMount("tools", null, null, MountAccessMode.RO),
                                new AgentDefinition.ExtraMount(null, null, "/skills", MountAccessMode.RW)
                        )
                ),
                List.of(),
                null,
                null,
                List.of(),
                null
        );

        Agent agent = new Agent() {
            @Override
            public String id() {
                return "sandboxed";
            }

            @Override
            public String providerKey() {
                return "bailian";
            }

            @Override
            public String model() {
                return "qwen3-max";
            }

            @Override
            public String systemPrompt() {
                return "sys";
            }

            @Override
            public Optional<AgentDefinition> definition() {
                return Optional.of(definition);
            }

            @Override
            public Flux<com.linlay.agentplatform.model.AgentDelta> stream(com.linlay.agentplatform.model.AgentRequest request) {
                return Flux.empty();
            }
        };

        when(agentRegistry.list()).thenReturn(List.of(agent));

        AgentCatalogController controller = new AgentCatalogController(
                agentRegistry,
                teamRegistryService,
                skillRegistryService,
                toolRegistry
        );

        ApiResponse<AgentDetailResponse> response = controller.agent("sandboxed");
        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) response.data().meta().get("sandbox");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> extraMounts = (List<Map<String, Object>>) sandbox.get("extraMounts");

        assertThat(extraMounts).containsExactly(expectedExtraMount("tools", null, null, "ro"), expectedExtraMount(null, null, "/skills", "rw"));
    }

    private Map<String, Object> expectedExtraMount(String platform, String source, String destination, String mode) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("platform", platform);
        item.put("source", source);
        item.put("destination", destination);
        item.put("mode", mode);
        return item;
    }
}
