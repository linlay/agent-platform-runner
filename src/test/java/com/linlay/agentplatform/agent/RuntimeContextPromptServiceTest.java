package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.RootProperties;
import com.linlay.agentplatform.config.DataProperties;
import com.linlay.agentplatform.memory.ChatWindowMemoryProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextPromptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRenderOwnerProfileSessionAndAuthSections() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime");
        Files.createDirectories(runtimeHome.resolve("configs"));
        Files.writeString(runtimeHome.resolve("OWNER.md"), """
                ---
                name: Linlay
                preferred_name: Linlay
                language: zh-CN
                timezone: Asia/Shanghai
                style: concise, direct
                ---

                # Working Preferences
                - Prefer Chinese unless asked otherwise.
                """);

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            RuntimeContextPromptService service = newService();
            RuntimeRequestContext.WorkspacePaths workspacePaths = service.resolveWorkspacePaths("chat-1");
            AgentRequest request = new AgentRequest(
                    "hello",
                    "chat-1",
                    "req-1",
                    "run-1",
                    Map.of(),
                    new RuntimeRequestContext(
                            "demo-agent",
                            "team-a",
                            "user",
                            "Demo Chat",
                            new QueryRequest.Scene("https://example.com", "Example"),
                            List.of(new QueryRequest.Reference("ref-1", "file", "notes.md", "text/markdown", 42L, null, null, null)),
                            new JwksJwtVerifier.JwtPrincipal("user-1", "device-1", "chat:write", Instant.parse("2026-03-20T10:15:30Z"), Instant.parse("2026-03-21T10:15:30Z")),
                            workspacePaths,
                            new RuntimeRequestContext.SandboxContext(
                                    "daily-office",
                                    "daily-office",
                                    "shell",
                                    "RUN",
                                    true,
                                    true,
                                    List.of("platform:tools (ro)", "destination:/skills (rw)"),
                                    "You are running inside the `daily-office` environment."
                            ),
                            List.of(
                                    new RuntimeRequestContext.AgentDigest(
                                            "commander",
                                            "Commander",
                                            "指挥官",
                                            "负责协同多个 agent",
                                            "REACT",
                                            "bailian-qwen3-max",
                                            List.of("delegate_task"),
                                            List.of("triage"),
                                            new RuntimeRequestContext.SandboxDigest("shell", "RUN")
                                    ),
                                    new RuntimeRequestContext.AgentDigest(
                                            "writer",
                                            "Writer",
                                            "文档助手",
                                            "负责文档写作",
                                            "ONESHOT",
                                            "bailian-qwen3-max",
                                            List.of(),
                                            List.of("docx"),
                                            null
                                    )
                            )
                    )
            );

            String prompt = service.buildPrompt(definition(
                    RuntimeContextTags.SYSTEM,
                    RuntimeContextTags.CONTEXT,
                    RuntimeContextTags.OWNER,
                    RuntimeContextTags.AUTH,
                    RuntimeContextTags.SANDBOX,
                    RuntimeContextTags.ALL_AGENTS
            ), request);

            assertThat(prompt).contains("Runtime Context: System Environment");
            assertThat(prompt).contains("Runtime Context: Context");
            assertThat(prompt).contains("data_dir: " + runtimeHome.resolve("data").toAbsolutePath().normalize());
            assertThat(prompt).contains("skills_market_dir: " + runtimeHome.resolve("skills-market").toAbsolutePath().normalize());
            assertThat(prompt).contains("chat_attachments_dir: " + runtimeHome.resolve("data").resolve("chat-1").toAbsolutePath().normalize());
            assertThat(prompt).contains("chatId: chat-1");
            assertThat(prompt).contains("chatName: Demo Chat");
            assertThat(prompt).contains("references: 1 item(s): notes.md (file)");
            assertThat(prompt).contains("Runtime Context: Owner Profile");
            assertThat(prompt).contains("name: Linlay");
            assertThat(prompt).contains("owner_notes:");
            assertThat(prompt).contains("Runtime Context: Auth Identity");
            assertThat(prompt).contains("subject: user-1");
            assertThat(prompt).contains("Runtime Context: Sandbox");
            assertThat(prompt).contains("environmentId: daily-office");
            assertThat(prompt).contains("environment_prompt:");
            assertThat(prompt).contains("Runtime Context: All Agents");
            assertThat(prompt).contains("key: commander");
            assertThat(prompt).contains("sandbox:");
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldTrimLargeOwnerBodyAndOmitMissingOwnerFile() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime");
        Files.createDirectories(runtimeHome.resolve("configs"));

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            RuntimeContextPromptService service = newService();
            RuntimeRequestContext.WorkspacePaths workspacePaths = service.resolveWorkspacePaths("chat-2");
            AgentRequest request = new AgentRequest(
                    "hello",
                    "chat-2",
                    "req-2",
                    "run-2",
                    Map.of(),
                    new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, workspacePaths, null, List.of())
            );

            String missingOwnerPrompt = service.buildPrompt(definition(RuntimeContextTags.OWNER), request);
            assertThat(missingOwnerPrompt).isEmpty();

            Files.writeString(runtimeHome.resolve("OWNER.md"), """
                    ---
                    name: Linlay
                    ---

                    %s
                    """.formatted("x".repeat(4_200)));

            String prompt = service.buildPrompt(definition(RuntimeContextTags.OWNER), request);
            assertThat(prompt).contains("Runtime Context: Owner Profile");
            assertThat(prompt).contains("name: Linlay");
            assertThat(prompt).contains("[TRUNCATED: OWNER.md exceeds max chars=4000]");
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldTruncateAllAgentsSectionWhenTooLarge() {
        RuntimeContextPromptService service = newService();
        List<RuntimeRequestContext.AgentDigest> digests = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            digests.add(new RuntimeRequestContext.AgentDigest(
                    "agent-" + i,
                    "Agent " + i,
                    "role",
                    "description " + "x".repeat(80),
                    "ONESHOT",
                    "model-key",
                    List.of("tool_a", "tool_b"),
                    List.of("skill_a", "skill_b"),
                    new RuntimeRequestContext.SandboxDigest("shell", "RUN")
            ));
        }

        AgentRequest request = new AgentRequest(
                "hello",
                "chat-3",
                "req-3",
                "run-3",
                Map.of(),
                new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, null, null, digests)
        );

        String prompt = service.buildPrompt(definition(RuntimeContextTags.ALL_AGENTS), request);

        assertThat(prompt).contains("Runtime Context: All Agents");
        assertThat(prompt).contains("[TRUNCATED: all-agents exceeds max chars=12000");
    }

    private RuntimeContextPromptService newService() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.agents.external-dir", "agents")
                .withProperty("agent.skills.external-dir", "skills-market")
                .withProperty("agent.schedule.external-dir", "schedules");
        RootProperties rootProperties = new RootProperties();
        rootProperties.setExternalDir("root");
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir("data");
        ChatWindowMemoryProperties memoryProperties = new ChatWindowMemoryProperties();
        memoryProperties.setDir("chats");
        return new RuntimeContextPromptService(environment, rootProperties, dataProperties, memoryProperties);
    }

    private AgentDefinition definition(String... contextTags) {
        return new AgentDefinition(
                "context-agent",
                "Context Agent",
                null,
                "context agent",
                "context role",
                null,
                "provider",
                "model",
                null,
                AgentRuntimeMode.ONESHOT,
                new RunSpec(ToolChoice.NONE, Budget.DEFAULT),
                new OneshotMode(
                        new StageSettings("yaml prompt", null, null, List.of(), false, ComputePolicy.MEDIUM, "plain markdown"),
                        null,
                        null
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                "soul prompt",
                null,
                List.of(contextTags),
                List.of(),
                tempDir.resolve("agent")
        );
    }

    private void restoreUserDir(String originalUserDir) {
        if (originalUserDir == null) {
            System.clearProperty("user.dir");
            return;
        }
        System.setProperty("user.dir", originalUserDir);
    }
}
