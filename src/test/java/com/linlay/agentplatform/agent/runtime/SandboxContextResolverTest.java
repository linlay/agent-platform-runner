package com.linlay.agentplatform.agent.runtime;

import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.properties.ContainerHubToolProperties;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.tool.ContainerHubClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SandboxContextResolverTest {

    @Test
    void resolveShouldBuildSandboxContextFromAgentAndProperties() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "daily-office",
                        true,
                        "You are running inside the `daily-office` environment.",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setEnabled(true);
        properties.setDefaultEnvironmentId("shell");
        properties.setDefaultSandboxLevel("edit");
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, properties);

        RuntimeRequestContext.SandboxContext context = resolver.resolve(
                agentDefinition(
                        List.of("sandbox_bash"),
                        new AgentDefinition.SandboxConfig(
                                "daily-office",
                                SandboxLevel.RUN,
                                List.of(new AgentDefinition.ExtraMount("tools", null, null, MountAccessMode.RO))
                        )
                ),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        );

        assertThat(context.environmentId()).isEqualTo("daily-office");
        assertThat(context.configuredEnvironmentId()).isEqualTo("daily-office");
        assertThat(context.defaultEnvironmentId()).isEqualTo("shell");
        assertThat(context.level()).isEqualTo("RUN");
        assertThat(context.containerHubEnabled()).isTrue();
        assertThat(context.usesContainerHubTool()).isTrue();
        assertThat(context.extraMounts()).containsExactly("platform:tools (ro)");
        assertThat(context.environmentPrompt()).contains("daily-office");
    }

    @Test
    void resolveShouldUseDefaultEnvironmentAndLevelWhenAgentDoesNotOverrideThem() {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("shell")).thenReturn(
                new ContainerHubClient.EnvironmentAgentPromptResult(
                        "shell",
                        true,
                        "Shell sandbox prompt",
                        Instant.parse("2026-03-22T10:15:30Z"),
                        null
                )
        );
        ContainerHubToolProperties properties = new ContainerHubToolProperties();
        properties.setDefaultEnvironmentId("shell");
        properties.setDefaultSandboxLevel("edit");
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, properties);

        RuntimeRequestContext.SandboxContext context = resolver.resolve(
                agentDefinition(List.of("sandbox_bash"), new AgentDefinition.SandboxConfig(null)),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        );

        assertThat(context.environmentId()).isEqualTo("shell");
        assertThat(context.configuredEnvironmentId()).isNull();
        assertThat(context.defaultEnvironmentId()).isEqualTo("shell");
        assertThat(context.level()).isEqualTo("EDIT");
        assertThat(context.usesContainerHubTool()).isTrue();
        assertThat(context.environmentPrompt()).isEqualTo("Shell sandbox prompt");
    }

    @Test
    void resolveShouldFailWhenPromptFetchFailsAndLogReason(CapturedOutput output) {
        ContainerHubClient containerHubClient = mock(ContainerHubClient.class);
        when(containerHubClient.getEnvironmentAgentPrompt("daily-office")).thenReturn(
                ContainerHubClient.EnvironmentAgentPromptResult.failure("daily-office", "hub unavailable")
        );
        SandboxContextResolver resolver = new SandboxContextResolver(containerHubClient, new ContainerHubToolProperties());

        assertThatThrownBy(() -> resolver.resolve(
                agentDefinition(List.of("sandbox_bash"), new AgentDefinition.SandboxConfig("daily-office")),
                "chat-1",
                "run-1",
                "demo-agent",
                null,
                "Chat Alpha"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox context failed to load environment prompt");
        assertThat(output.getAll()).contains("Sandbox agent prompt fetch failed");
        assertThat(output.getAll()).contains("environmentId=daily-office");
    }

    private AgentDefinition agentDefinition(List<String> tools, AgentDefinition.SandboxConfig sandboxConfig) {
        return new AgentDefinition(
                "demo-agent",
                "Demo Agent",
                null,
                "Demo Description",
                "Demo Role",
                "model-key",
                "provider",
                "model-id",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.NONE, Budget.DEFAULT),
                new OneshotMode(new StageSettings("prompt", null, null, tools, false, ComputePolicy.MEDIUM), null, null),
                tools,
                List.of("docx"),
                List.of(),
                sandboxConfig,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                null
        );
    }
}
