package com.linlay.agentplatform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linlay.agentplatform.agent.AgentDefinition;
import com.linlay.agentplatform.config.ContainerHubToolProperties;
import com.linlay.agentplatform.tool.ContainerHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ContainerHubSandboxService implements DisposableBean {

    public static final String TOOL_NAME = "container_hub_bash";
    private static final Logger log = LoggerFactory.getLogger(ContainerHubSandboxService.class);
    private static final String DEFAULT_WORKSPACE_CWD = "/workspace";

    private final ContainerHubToolProperties properties;
    private final ContainerHubClient client;
    private final ContainerHubMountResolver mountResolver;

    private final ConcurrentHashMap<String, ManagedSession> agentSessions = new ConcurrentHashMap<>();
    private volatile ManagedSession globalSession;
    private final Object globalSessionLock = new Object();

    private final ScheduledExecutorService destroyScheduler;
    private final ScheduledExecutorService idleEvictionScheduler;

    public ContainerHubSandboxService(
            ContainerHubToolProperties properties,
            ContainerHubClient client,
            ContainerHubMountResolver mountResolver
    ) {
        this.properties = properties;
        this.client = client;
        this.mountResolver = mountResolver;
        this.destroyScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "container-hub-destroy");
            t.setDaemon(true);
            return t;
        });
        this.idleEvictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "container-hub-idle-eviction");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean requiresSandbox(AgentDefinition definition) {
        return definition != null && definition.tools().stream().anyMatch(TOOL_NAME::equals);
    }

    public void openIfNeeded(ExecutionContext context) {
        if (context == null || !requiresSandbox(context.definition())) {
            return;
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("container-hub sandbox is disabled. Configure agent.tools.container-hub.enabled=true");
        }
        String environmentId = resolveEnvironmentId(context.definition());
        if (!StringUtils.hasText(environmentId)) {
            throw new IllegalStateException("container-hub environmentId is required for agent " + context.definition().id());
        }

        SandboxLevel level = resolveLevel(context.definition());
        switch (level) {
            case RUN -> openRunSession(context, environmentId);
            case AGENT -> acquireAgentSession(context, environmentId);
            case GLOBAL -> acquireGlobalSession(context, environmentId);
        }
    }

    public void closeQuietly(ExecutionContext context) {
        if (context == null || context.sandboxSession() == null) {
            return;
        }
        ExecutionContext.SandboxSession session = context.sandboxSession();
        SandboxLevel level = session.level();
        try {
            switch (level != null ? level : SandboxLevel.RUN) {
                case RUN -> scheduleAsyncDestroy(session.sessionId());
                case AGENT -> {
                    String agentKey = context.definition() != null ? context.definition().id() : null;
                    releaseAgentSession(agentKey);
                }
                case GLOBAL -> {
                    // global session: no-op on close, stays alive
                }
            }
        } catch (Exception ex) {
            log.warn("container-hub sandbox close failed for runId={}, sessionId={}, level={}",
                    context.request().runId(), session.sessionId(), level, ex);
        } finally {
            context.clearSandboxSession();
        }
    }

    private void openRunSession(ExecutionContext context, String environmentId) {
        String sessionId = buildSessionId("run", context.request().runId());
        SandboxLevel level = SandboxLevel.RUN;
        List<ContainerHubMountResolver.MountSpec> mounts = mountResolver.resolve(
                level,
                context.request().chatId(),
                context.definition().id(),
                resolveExtraMounts(context.definition())
        );

        ObjectNode payload = buildCreatePayload(sessionId, environmentId, buildLabels(context), mounts, DEFAULT_WORKSPACE_CWD);
        JsonNode response = client.createSession(payload);
        if (isErrorResponse(response)) {
            throw new IllegalStateException("container-hub sandbox create failed: " + readError(response));
        }

        String returnedSessionId = readText(response, "session_id");
        if (!StringUtils.hasText(returnedSessionId)) {
            throw new IllegalStateException("container-hub sandbox create failed: missing session_id");
        }
        String defaultCwd = readText(response, "cwd");
        if (!StringUtils.hasText(defaultCwd)) {
            defaultCwd = DEFAULT_WORKSPACE_CWD;
        }
        context.bindSandboxSession(new ExecutionContext.SandboxSession(
                returnedSessionId,
                environmentId,
                effectiveCwd(level, context.request().chatId()),
                level
        ));
    }

    private void acquireAgentSession(ExecutionContext context, String environmentId) {
        String agentKey = context.definition().id();
        ManagedSession managed = agentSessions.compute(agentKey, (key, existing) -> {
            if (existing != null) {
                existing.activeUsers.incrementAndGet();
                existing.lastAccessedMs.set(System.currentTimeMillis());
                return existing;
            }
            // create new session
            String sessionId = buildSessionId("agent", agentKey);
            List<ContainerHubMountResolver.MountSpec> mounts = mountResolver.resolve(
                    SandboxLevel.AGENT,
                    context.request().chatId(),
                    context.definition().id(),
                    resolveExtraMounts(context.definition())
            );
            ObjectNode payload = buildCreatePayload(sessionId, environmentId, buildLabels(context), mounts, DEFAULT_WORKSPACE_CWD);
            JsonNode response = client.createSession(payload);
            if (isErrorResponse(response)) {
                throw new IllegalStateException("container-hub sandbox create failed: " + readError(response));
            }
            String returnedSessionId = readText(response, "session_id");
            if (!StringUtils.hasText(returnedSessionId)) {
                throw new IllegalStateException("container-hub sandbox create failed: missing session_id");
            }
            String defaultCwd = readText(response, "cwd");
            if (!StringUtils.hasText(defaultCwd)) {
                defaultCwd = DEFAULT_WORKSPACE_CWD;
            }
            ManagedSession session = new ManagedSession(
                    returnedSessionId, environmentId, defaultCwd,
                    SandboxLevel.AGENT, agentKey,
                    new AtomicLong(System.currentTimeMillis()),
                    new AtomicInteger(1)
            );
            log.info("container-hub agent session created, agentKey={}, sessionId={}", agentKey, returnedSessionId);
            return session;
        });

        context.bindSandboxSession(new ExecutionContext.SandboxSession(
                managed.sessionId,
                managed.environmentId,
                effectiveCwd(SandboxLevel.AGENT, context.request().chatId()),
                SandboxLevel.AGENT
        ));
    }

    private void releaseAgentSession(String agentKey) {
        if (!StringUtils.hasText(agentKey)) {
            return;
        }
        ManagedSession managed = agentSessions.get(agentKey);
        if (managed == null) {
            return;
        }
        int remaining = managed.activeUsers.decrementAndGet();
        managed.lastAccessedMs.set(System.currentTimeMillis());
        if (remaining <= 0) {
            scheduleIdleEviction(agentKey);
        }
    }

    private void acquireGlobalSession(ExecutionContext context, String environmentId) {
        ManagedSession current = globalSession;
        if (current != null) {
            current.activeUsers.incrementAndGet();
            current.lastAccessedMs.set(System.currentTimeMillis());
            context.bindSandboxSession(new ExecutionContext.SandboxSession(
                    current.sessionId,
                    current.environmentId,
                    effectiveCwd(SandboxLevel.GLOBAL, context.request().chatId()),
                    SandboxLevel.GLOBAL
            ));
            return;
        }
        synchronized (globalSessionLock) {
            current = globalSession;
            if (current != null) {
                current.activeUsers.incrementAndGet();
                current.lastAccessedMs.set(System.currentTimeMillis());
                context.bindSandboxSession(new ExecutionContext.SandboxSession(
                        current.sessionId,
                        current.environmentId,
                        effectiveCwd(SandboxLevel.GLOBAL, context.request().chatId()),
                        SandboxLevel.GLOBAL
                ));
                return;
            }
            String sessionId = "global-singleton";
            List<ContainerHubMountResolver.MountSpec> mounts = mountResolver.resolve(
                    SandboxLevel.GLOBAL,
                    context.request().chatId(),
                    context.definition().id(),
                    resolveExtraMounts(context.definition())
            );
            ObjectNode payload = buildCreatePayload(sessionId, environmentId, buildLabels(context), mounts, DEFAULT_WORKSPACE_CWD);
            JsonNode response = client.createSession(payload);
            if (isErrorResponse(response)) {
                throw new IllegalStateException("container-hub sandbox create failed: " + readError(response));
            }
            String returnedSessionId = readText(response, "session_id");
            if (!StringUtils.hasText(returnedSessionId)) {
                throw new IllegalStateException("container-hub sandbox create failed: missing session_id");
            }
            String defaultCwd = readText(response, "cwd");
            if (!StringUtils.hasText(defaultCwd)) {
                defaultCwd = DEFAULT_WORKSPACE_CWD;
            }
            ManagedSession session = new ManagedSession(
                    returnedSessionId, environmentId, defaultCwd,
                    SandboxLevel.GLOBAL, null,
                    new AtomicLong(System.currentTimeMillis()),
                    new AtomicInteger(1)
            );
            this.globalSession = session;
            log.info("container-hub global session created, sessionId={}", returnedSessionId);
            context.bindSandboxSession(new ExecutionContext.SandboxSession(
                    returnedSessionId,
                    environmentId,
                    effectiveCwd(SandboxLevel.GLOBAL, context.request().chatId()),
                    SandboxLevel.GLOBAL
            ));
        }
    }

    private void scheduleAsyncDestroy(String sessionId) {
        long delayMs = properties.getDestroyQueueDelayMs();
        destroyScheduler.schedule(() -> {
            try {
                JsonNode response = client.stopSession(sessionId);
                if (isErrorResponse(response)) {
                    log.warn("container-hub async destroy failed, sessionId={}, error={}", sessionId, readError(response));
                }
            } catch (Exception ex) {
                log.warn("container-hub async destroy failed, sessionId={}", sessionId, ex);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleIdleEviction(String agentKey) {
        long idleTimeoutMs = properties.getAgentIdleTimeoutMs();
        idleEvictionScheduler.schedule(() -> {
            ManagedSession managed = agentSessions.get(agentKey);
            if (managed == null) {
                return;
            }
            if (managed.activeUsers.get() > 0) {
                return;
            }
            long idleMs = System.currentTimeMillis() - managed.lastAccessedMs.get();
            if (idleMs < idleTimeoutMs) {
                // not idle long enough, reschedule
                long remainingMs = idleTimeoutMs - idleMs;
                idleEvictionScheduler.schedule(() -> scheduleIdleEviction(agentKey), remainingMs, TimeUnit.MILLISECONDS);
                return;
            }
            // evict
            ManagedSession removed = agentSessions.remove(agentKey);
            if (removed != null && removed.activeUsers.get() <= 0) {
                log.info("container-hub evicting idle agent session, agentKey={}, sessionId={}, idleMs={}",
                        agentKey, removed.sessionId, idleMs);
                try {
                    client.stopSession(removed.sessionId);
                } catch (Exception ex) {
                    log.warn("container-hub idle eviction stop failed, agentKey={}, sessionId={}", agentKey, removed.sessionId, ex);
                }
            } else if (removed != null) {
                // put back if someone re-acquired during eviction
                agentSessions.putIfAbsent(agentKey, removed);
            }
        }, idleTimeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        log.info("container-hub sandbox service shutting down, stopping all managed sessions");
        destroyScheduler.shutdownNow();
        idleEvictionScheduler.shutdownNow();

        // stop agent sessions
        for (Map.Entry<String, ManagedSession> entry : agentSessions.entrySet()) {
            try {
                client.stopSession(entry.getValue().sessionId);
                log.info("container-hub stopped agent session, agentKey={}, sessionId={}",
                        entry.getKey(), entry.getValue().sessionId);
            } catch (Exception ex) {
                log.warn("container-hub failed to stop agent session on shutdown, agentKey={}", entry.getKey(), ex);
            }
        }
        agentSessions.clear();

        // stop global session
        ManagedSession global = this.globalSession;
        if (global != null) {
            try {
                client.stopSession(global.sessionId);
                log.info("container-hub stopped global session, sessionId={}", global.sessionId);
            } catch (Exception ex) {
                log.warn("container-hub failed to stop global session on shutdown", ex);
            }
            this.globalSession = null;
        }
    }

    SandboxLevel resolveLevel(AgentDefinition definition) {
        if (definition != null && definition.sandboxConfig() != null
                && definition.sandboxConfig().level() != null) {
            return definition.sandboxConfig().level();
        }
        SandboxLevel fromDefault = SandboxLevel.parse(properties.getDefaultSandboxLevel());
        return fromDefault != null ? fromDefault : SandboxLevel.RUN;
    }

    private String resolveEnvironmentId(AgentDefinition definition) {
        if (definition != null && definition.sandboxConfig() != null
                && StringUtils.hasText(definition.sandboxConfig().environmentId())) {
            return definition.sandboxConfig().environmentId().trim();
        }
        if (StringUtils.hasText(properties.getDefaultEnvironmentId())) {
            return properties.getDefaultEnvironmentId().trim();
        }
        return null;
    }

    private List<AgentDefinition.ExtraMount> resolveExtraMounts(AgentDefinition definition) {
        if (definition == null || definition.sandboxConfig() == null || definition.sandboxConfig().extraMounts() == null) {
            return List.of();
        }
        return definition.sandboxConfig().extraMounts();
    }

    private ObjectNode buildCreatePayload(
            String sessionId,
            String environmentId,
            Map<String, String> labels,
            List<ContainerHubMountResolver.MountSpec> mounts,
            String cwd
    ) {
        ObjectNode payload = ExecutionContext.OBJECT_MAPPER.createObjectNode();
        payload.put("session_id", sessionId);
        payload.put("environment_name", environmentId);
        if (StringUtils.hasText(cwd)) {
            payload.put("cwd", cwd.trim());
        }
        payload.set("labels", ExecutionContext.OBJECT_MAPPER.valueToTree(labels));

        if (mounts != null && !mounts.isEmpty()) {
            ArrayNode mountsArray = ExecutionContext.OBJECT_MAPPER.createArrayNode();
            for (ContainerHubMountResolver.MountSpec mount : mounts) {
                ObjectNode mountNode = ExecutionContext.OBJECT_MAPPER.createObjectNode();
                mountNode.put("source", mount.hostPath());
                mountNode.put("destination", mount.containerPath());
                mountsArray.add(mountNode);
            }
            payload.set("mounts", mountsArray);
        }

        return payload;
    }

    private String effectiveCwd(SandboxLevel level, String chatId) {
        if (level == SandboxLevel.RUN || !StringUtils.hasText(chatId)) {
            return DEFAULT_WORKSPACE_CWD;
        }
        return DEFAULT_WORKSPACE_CWD + "/" + chatId.trim();
    }

    private Map<String, String> buildLabels(ExecutionContext context) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("managed-by", "agent-platform-runner");
        labels.put("chatId", context.request().chatId());
        labels.put("runId", context.request().runId());
        labels.put("agentKey", context.definition().id());
        labels.values().removeIf(value -> !StringUtils.hasText(value));
        return labels;
    }

    private String buildSessionId(String prefix, String identifier) {
        String normalized = StringUtils.hasText(identifier)
                ? identifier.trim().toLowerCase(Locale.ROOT) : "unknown";
        normalized = normalized.replaceAll("[^a-z0-9_.-]", "-");
        return prefix + "-" + normalized;
    }

    private boolean isErrorResponse(JsonNode response) {
        return response != null && response.isObject() && response.path("ok").asBoolean(true) == false;
    }

    private String readError(JsonNode response) {
        String error = readText(response, "error");
        return StringUtils.hasText(error) ? error : "unknown container-hub error";
    }

    private String readText(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull() || !field.isValueNode()) {
            return null;
        }
        String value = field.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    record ManagedSession(
            String sessionId,
            String environmentId,
            String defaultCwd,
            SandboxLevel level,
            String agentKey,
            AtomicLong lastAccessedMs,
            AtomicInteger activeUsers
    ) {
    }
}
