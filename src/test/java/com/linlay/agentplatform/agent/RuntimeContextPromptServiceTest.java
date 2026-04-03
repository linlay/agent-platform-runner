package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.OneshotMode;
import com.linlay.agentplatform.agent.mode.StageSettings;
import com.linlay.agentplatform.agent.runtime.AgentRuntimeMode;
import com.linlay.agentplatform.agent.runtime.MountAccessMode;
import com.linlay.agentplatform.agent.runtime.SandboxLevel;
import com.linlay.agentplatform.agent.runtime.policy.Budget;
import com.linlay.agentplatform.agent.runtime.policy.ComputePolicy;
import com.linlay.agentplatform.agent.runtime.policy.RunSpec;
import com.linlay.agentplatform.agent.runtime.policy.ToolChoice;
import com.linlay.agentplatform.config.RuntimeDirectoryHostPaths;
import com.linlay.agentplatform.config.properties.AgentMemoryProperties;
import com.linlay.agentplatform.config.properties.DataProperties;
import com.linlay.agentplatform.config.properties.MemoryStorageProperties;
import com.linlay.agentplatform.config.properties.OwnerProperties;
import com.linlay.agentplatform.config.properties.RootProperties;
import com.linlay.agentplatform.chatstorage.ChatStorageProperties;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.RuntimeRequestContext;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.security.JwksJwtVerifier;
import com.linlay.agentplatform.service.memory.AgentMemoryStore;
import com.linlay.agentplatform.service.memory.MemoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeContextPromptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPreferOwnerDirFromHostPathsForContextAndOwnerSections() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime");
        Path externalOwner = tempDir.resolve("external-owner");
        Files.createDirectories(runtimeHome.resolve("configs"));
        Files.createDirectories(runtimeHome.resolve("owner"));
        Files.writeString(runtimeHome.resolve("owner").resolve("OWNER.md"), "fallback owner");
        Files.createDirectories(externalOwner);
        Files.writeString(externalOwner.resolve("OWNER.md"), """
                ---
                name: External Owner
                ---

                external owner body
                """);
        Files.writeString(externalOwner.resolve("BOOTSTRAP.md"), """
                # Bootstrap

                external bootstrap
                """);

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            Files.createDirectories(runtimeHome.resolve("agents").resolve("context-agent"));
            RuntimeContextPromptService service = newService(
                    externalOwner.toString(),
                    null
            );
            AgentDefinition definition = definition(
                    new AgentDefinition.SandboxConfig(
                            null,
                            SandboxLevel.RUN,
                            List.of(new AgentDefinition.ExtraMount("owner", null, null, MountAccessMode.RO))
                    ),
                    RuntimeContextTags.CONTEXT,
                    RuntimeContextTags.OWNER
            );
            RuntimeRequestContext.LocalPaths localPaths = service.resolveLocalPaths("chat-ext");
            RuntimeRequestContext.SandboxPaths sandboxPaths = service.resolveSandboxPaths(definition, "chat-ext", "run");
            AgentRequest request = new AgentRequest(
                    "hello",
                    "chat-ext",
                    "req-ext",
                    "run-ext",
                    Map.of(),
                    new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, localPaths, sandboxPaths, null, List.of())
            );

            String prompt = service.buildPrompt(definition, request);

            assertThat(localPaths.ownerDir()).isEqualTo(externalOwner.toAbsolutePath().normalize().toString());
            assertThat(prompt).contains("sandbox_owner_dir: /owner");
            assertThat(prompt).contains("sandbox_agent_dir: /agent");
            assertThat(prompt).contains("Runtime Context: Owner");
            assertThat(prompt).contains("--- file: BOOTSTRAP.md");
            assertThat(prompt).contains("--- file: OWNER.md");
            assertThat(prompt).contains("External Owner");
            assertThat(prompt).contains("external bootstrap");
            assertThat(prompt).doesNotContain("fallback owner");
            assertThat(prompt).doesNotContain("\nowner_dir:");
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldRenderOwnerProfileSessionAndAuthSections() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime");
        Files.createDirectories(runtimeHome.resolve("configs"));
        Files.createDirectories(runtimeHome.resolve("owner"));
        Files.writeString(runtimeHome.resolve("owner").resolve("OWNER.md"), """
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
        Files.writeString(runtimeHome.resolve("owner").resolve("BOOTSTRAP.md"), """
                # Bootstrap

                Initialize owner state.
                """);

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            Files.createDirectories(runtimeHome.resolve("agents").resolve("context-agent"));
            RuntimeContextPromptService service = newService(null);
            AgentDefinition definition = definition(
                    new AgentDefinition.SandboxConfig(
                            "daily-office",
                            SandboxLevel.RUN,
                        List.of(
                                    new AgentDefinition.ExtraMount("owner", null, null, MountAccessMode.RO),
                                    new AgentDefinition.ExtraMount("providers", null, null, MountAccessMode.RO),
                                    new AgentDefinition.ExtraMount("skills-market", null, null, MountAccessMode.RO)
                            )
                    ),
                    RuntimeContextTags.SYSTEM,
                    RuntimeContextTags.CONTEXT,
                    RuntimeContextTags.OWNER,
                    RuntimeContextTags.AUTH,
                    RuntimeContextTags.SANDBOX,
                    RuntimeContextTags.ALL_AGENTS
            );
            RuntimeRequestContext.LocalPaths localPaths = service.resolveLocalPaths("chat-1");
            RuntimeRequestContext.SandboxPaths sandboxPaths = service.resolveSandboxPaths(definition, "chat-1", "run");
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
                            List.of(new QueryRequest.Reference("ref-1", "file", "notes.md", "text/markdown", 42L, null, null, "/workspace/notes.md", null)),
                            new JwksJwtVerifier.JwtPrincipal("user-1", "device-1", "chat:write", Instant.parse("2026-03-20T10:15:30Z"), Instant.parse("2026-03-21T10:15:30Z")),
                            localPaths,
                            sandboxPaths,
                            new RuntimeRequestContext.SandboxContext(
                                    "daily-office",
                                    "daily-office",
                                    "shell",
                                    "RUN",
                                    true,
                                    true,
                                    List.of("platform:tools (ro)", "platform:skills-market (ro)", "destination:/skills (rw)"),
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

            String prompt = service.buildPrompt(definition, request);

            assertThat(prompt).contains("Runtime Context: System Environment");
            assertThat(prompt).contains("Runtime Context: Context");
            assertThat(prompt).contains("sandbox_cwd: /workspace");
            assertThat(prompt).contains("sandbox_workspace_dir: /workspace");
            assertThat(prompt).contains("sandbox_root_dir: /root");
            assertThat(prompt).contains("sandbox_skills_dir: /skills");
            assertThat(prompt).contains("sandbox_skills_market_dir: /skills-market");
            assertThat(prompt).contains("sandbox_pan_dir: /pan");
            assertThat(prompt).contains("sandbox_agent_dir: /agent");
            assertThat(prompt).contains("sandbox_owner_dir: /owner");
            assertThat(prompt).contains("sandbox_providers_dir: /providers");
            assertThat(prompt).doesNotContain("sandbox_schedules_dir:");
            assertThat(prompt).contains("chatId: chat-1");
            assertThat(prompt).contains("chatName: Demo Chat");
            assertThat(prompt).contains("references:");
            assertThat(prompt).contains("  - id: ref-1");
            assertThat(prompt).contains("    sandboxPath: /workspace/notes.md");
            assertThat(prompt).contains("    name: notes.md");
            assertThat(prompt).contains("    sizeBytes: 42");
            assertThat(prompt).contains("    mimeType: text/markdown");
            assertThat(prompt).contains("Runtime Context: Owner");
            assertThat(prompt).contains("--- file: BOOTSTRAP.md");
            assertThat(prompt).contains("--- file: OWNER.md");
            assertThat(prompt).contains("name: Linlay");
            assertThat(prompt).contains("# Working Preferences");
            assertThat(prompt).contains("Runtime Context: Auth Identity");
            assertThat(prompt).contains("subject: user-1");
            assertThat(prompt).contains("Runtime Context: Sandbox");
            assertThat(prompt).contains("environmentId: daily-office");
            assertThat(prompt).contains("environment_prompt:");
            assertThat(prompt).contains("- platform:skills-market (ro)");
            assertThat(prompt).contains("Runtime Context: All Agents");
            assertThat(prompt).contains("key: commander");
            assertThat(prompt).contains("sandbox:");
            assertThat(prompt).doesNotContain("/opt/");
            assertThat(prompt).doesNotContain("runner_working_directory:");
            assertThat(prompt).doesNotContain("runtime_home:");
            assertThat(prompt).doesNotContain("data_dir:");
            assertThat(prompt).doesNotContain("\nowner_dir:");
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldRenderAllOwnerMarkdownFilesInDeterministicOrder() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime");
        Files.createDirectories(runtimeHome.resolve("configs"));
        Files.createDirectories(runtimeHome.resolve("owner").resolve("nested"));

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            RuntimeContextPromptService service = newService(null);
            RuntimeRequestContext.LocalPaths localPaths = service.resolveLocalPaths("chat-2");
            AgentRequest request = new AgentRequest(
                    "hello",
                    "chat-2",
                    "req-2",
                    "run-2",
                    Map.of(),
                    new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, localPaths, null, null, List.of())
            );

            assertThat(localPaths.ownerDir()).isEqualTo(runtimeHome.resolve("owner").toAbsolutePath().normalize().toString());
            String missingOwnerPrompt = service.buildPrompt(definition(RuntimeContextTags.OWNER), request);
            assertThat(missingOwnerPrompt).isEmpty();

            Files.writeString(runtimeHome.resolve("owner").resolve("z-last.md"), "last file");
            Files.writeString(runtimeHome.resolve("owner").resolve("nested").resolve("a-first.markdown"), "nested file");
            Files.writeString(runtimeHome.resolve("owner").resolve("OWNER.md"), "owner file");
            Files.writeString(runtimeHome.resolve("owner").resolve("notes.txt"), "ignore me");

            String prompt = service.buildPrompt(definition(RuntimeContextTags.OWNER), request);
            assertThat(prompt).contains("Runtime Context: Owner");
            assertThat(prompt).contains("--- file: OWNER.md");
            assertThat(prompt).contains("--- file: nested/a-first.markdown");
            assertThat(prompt).contains("--- file: z-last.md");
            assertThat(prompt).doesNotContain("ignore me");

            int ownerIndex = prompt.indexOf("--- file: OWNER.md");
            int nestedIndex = prompt.indexOf("--- file: nested/a-first.markdown");
            int lastIndex = prompt.indexOf("--- file: z-last.md");
            assertThat(ownerIndex).isLessThan(nestedIndex);
            assertThat(nestedIndex).isLessThan(lastIndex);
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldTruncateAllAgentsSectionWhenTooLarge() {
        RuntimeContextPromptService service = newService(null);
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
                new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, null, null, null, digests)
        );

        String prompt = service.buildPrompt(definition(RuntimeContextTags.ALL_AGENTS), request);

        assertThat(prompt).contains("Runtime Context: All Agents");
        assertThat(prompt).contains("[TRUNCATED: all-agents exceeds max chars=12000");
    }

    @Test
    void shouldUseChatScopedCwdForAgentAndGlobalSandboxLevels() throws Exception {
        Path runtimeHome = tempDir.resolve("runtime-cwd");
        Files.createDirectories(runtimeHome.resolve("configs"));
        Files.createDirectories(runtimeHome.resolve("agents").resolve("context-agent"));

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", runtimeHome.toString());
        try {
            RuntimeContextPromptService service = newService(null);
            AgentDefinition agentLevelDefinition = definition(
                    new AgentDefinition.SandboxConfig(null, SandboxLevel.AGENT, List.of()),
                    RuntimeContextTags.CONTEXT
            );
            AgentDefinition globalLevelDefinition = definition(
                    new AgentDefinition.SandboxConfig(null, SandboxLevel.GLOBAL, List.of()),
                    RuntimeContextTags.CONTEXT
            );

            assertThat(service.resolveSandboxPaths(agentLevelDefinition, "chat-agent", "run").cwd())
                    .isEqualTo("/workspace/chat-agent");
            assertThat(service.resolveSandboxPaths(globalLevelDefinition, "chat-global", "run").cwd())
                    .isEqualTo("/workspace/chat-global");
        } finally {
            restoreUserDir(originalUserDir);
        }
    }

    @Test
    void shouldRenderAgentMemorySectionFromRelevantSearch() {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        AgentMemoryProperties agentMemoryProperties = new AgentMemoryProperties();
        agentMemoryProperties.setEnabled(true);
        agentMemoryProperties.setContextTopN(2);
        RuntimeContextPromptService service = newService("owner", null, store, agentMemoryProperties);
        when(store.topRelevant("context-agent", tempDir.resolve("agent"), "remember", 2)).thenReturn(List.of(
                new MemoryRecord("mem_1", "context-agent", "chat:chat-memory", "alpha memory", "remember", "fact", 9, List.of("alpha"), false, null, 1L, 1L, 0, null)
        ));

        AgentRequest request = new AgentRequest(
                "remember",
                "chat-memory",
                "req-memory",
                "run-memory",
                Map.of(),
                new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, service.resolveLocalPaths("chat-memory"), null, null, List.of())
        );

        String prompt = service.buildPrompt(definition(RuntimeContextTags.MEMORY), request);

        assertThat(prompt).contains("Runtime Context: Agent Memory");
        assertThat(prompt).contains("id: mem_1");
        assertThat(prompt).contains("content: alpha memory");
    }

    @Test
    void shouldTruncateAgentMemorySection() {
        AgentMemoryStore store = mock(AgentMemoryStore.class);
        AgentMemoryProperties agentMemoryProperties = new AgentMemoryProperties();
        agentMemoryProperties.setEnabled(true);
        agentMemoryProperties.setContextTopN(1);
        agentMemoryProperties.setContextMaxChars(80);
        RuntimeContextPromptService service = newService("owner", null, store, agentMemoryProperties);
        when(store.list("context-agent", tempDir.resolve("agent"), null, 1, "importance")).thenReturn(List.of(
                new MemoryRecord("mem_1", "context-agent", "chat:chat-memory", "x".repeat(200), "remember", "fact", 9, List.of(), false, null, 1L, 1L, 0, null)
        ));

        AgentRequest request = new AgentRequest(
                "",
                "chat-memory",
                "req-memory",
                "run-memory",
                Map.of(),
                new RuntimeRequestContext("demo-agent", null, "user", null, null, List.of(), null, service.resolveLocalPaths("chat-memory"), null, null, List.of())
        );

        String prompt = service.buildPrompt(definition(RuntimeContextTags.MEMORY), request);

        assertThat(prompt).contains("[TRUNCATED: agent-memory exceeds max chars=80]");
    }

    private RuntimeContextPromptService newService() {
        return newService(null);
    }

    private RuntimeContextPromptService newService(RuntimeDirectoryHostPaths hostPaths) {
        return newService("owner", hostPaths);
    }

    private RuntimeContextPromptService newService(String ownerDir, RuntimeDirectoryHostPaths hostPaths) {
        return newService(ownerDir, hostPaths, null, null);
    }

    private RuntimeContextPromptService newService(
            String ownerDir,
            RuntimeDirectoryHostPaths hostPaths,
            AgentMemoryStore agentMemoryStore,
            AgentMemoryProperties agentMemoryProperties
    ) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.agents.external-dir", "agents")
                .withProperty("agent.pan.external-dir", "pan")
                .withProperty("agent.skills.external-dir", "skills-market")
                .withProperty("agent.schedule.external-dir", "schedules")
                .withProperty("agent.providers.external-dir", "registries/providers");
        RootProperties rootProperties = new RootProperties();
        rootProperties.setExternalDir("root");
        OwnerProperties ownerProperties = new OwnerProperties();
        ownerProperties.setExternalDir(ownerDir);
        DataProperties dataProperties = new DataProperties();
        dataProperties.setExternalDir("data");
        ChatStorageProperties memoryProperties = new ChatStorageProperties();
        memoryProperties.setDir("chats");
        MemoryStorageProperties storageProperties = new MemoryStorageProperties();
        storageProperties.setDir("memory");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (agentMemoryStore != null) {
            beanFactory.addBean("agentMemoryStore", agentMemoryStore);
        }
        if (agentMemoryProperties != null) {
            beanFactory.addBean("agentMemoryProperties", agentMemoryProperties);
        }
        return new RuntimeContextPromptService(
                environment,
                rootProperties,
                ownerProperties,
                dataProperties,
                memoryProperties,
                storageProperties,
                beanFactory.getBeanProvider(AgentMemoryStore.class),
                beanFactory.getBeanProvider(AgentMemoryProperties.class)
        );
    }

    private AgentDefinition definition(String... contextTags) {
        return definition(null, contextTags);
    }

    private AgentDefinition definition(AgentDefinition.SandboxConfig sandboxConfig, String... contextTags) {
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
                sandboxConfig,
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
