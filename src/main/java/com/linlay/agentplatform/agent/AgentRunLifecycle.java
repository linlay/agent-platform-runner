package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.OrchestratorServices;
import com.linlay.agentplatform.agent.runtime.BudgetExceededException;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.FatalToolExecutionException;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitTimeoutException;
import com.linlay.agentplatform.agent.runtime.ModelTimeoutException;
import com.linlay.agentplatform.agent.runtime.RunInterruptedException;
import com.linlay.agentplatform.agent.runtime.RunLoopState;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.tool.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.FluxSink;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentRunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentRunLifecycle.class);
    private static final String FRONTEND_TIMEOUT_FALLBACK_MESSAGE = "前端工具等待用户提交超时，本次运行已结束。请重新发起或在超时前提交。";

    private final String agentId;
    private final ContainerHubSandboxService containerHubSandboxService;

    public AgentRunLifecycle(String agentId, ContainerHubSandboxService containerHubSandboxService) {
        this.agentId = agentId;
        this.containerHubSandboxService = containerHubSandboxService;
    }

    public void run(
            AgentDefinition definition,
            ExecutionContext context,
            Map<String, BaseTool> configuredToolsByName,
            OrchestratorServices services,
            FluxSink<AgentDelta> sink
    ) {
        context.bindRunnerThread(Thread.currentThread());
        context.runControl().transitionState(RunLoopState.IDLE);
        try {
            openSandboxIfNeeded(context);
            if (context.hasPlan()) {
                services.emit(sink, AgentDelta.planUpdate(context.planId(), context.request().chatId(), context.planTasks()));
            }
            definition.agentMode().run(context, configuredToolsByName, services, sink);
            if (context.isInterrupted()) {
                throw new RunInterruptedException();
            }
            services.emit(sink, AgentDelta.finish("stop"));
            context.runControl().transitionState(RunLoopState.COMPLETED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (RunInterruptedException ex) {
            context.runControl().transitionState(RunLoopState.CANCELLED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (FatalToolExecutionException ex) {
            log.info("[agent:{}] fatal tool error code={}, message={}", agentId, ex.code(), ex.getMessage());
            emitFailure(services, sink, context, fatalToolFailure(ex, context));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (FrontendSubmitTimeoutException ex) {
            log.info("[agent:{}] frontend submit timeout: {}", agentId, ex.getMessage());
            if (StringUtils.hasText(ex.toolId()) && StringUtils.hasText(ex.resultText())) {
                services.emit(sink, AgentDelta.toolResult(ex.toolId(), ex.resultText()));
            }
            emitFailure(services, sink, context, frontendSubmitTimeoutFailure(ex, context));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (ModelTimeoutException ex) {
            log.warn("[agent:{}] model timeout stage={}, message={}", agentId, ex.stage(), ex.getMessage());
            emitFailure(services, sink, context, modelTimeoutFailure(ex, context));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (BudgetExceededException ex) {
            log.warn("[agent:{}] budget exceeded kind={}, message={}", agentId, ex.kind(), ex.getMessage());
            emitFailure(services, sink, context, budgetExceededFailure(ex, context));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (Exception ex) {
            log.warn("[agent:{}] orchestration failed", agentId, ex);
            emitFailure(services, sink, context, internalFailure(context));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } finally {
            context.bindRunnerThread(null);
        }
    }

    private void openSandboxIfNeeded(ExecutionContext context) {
        if (containerHubSandboxService == null) {
            return;
        }
        try {
            containerHubSandboxService.openIfNeeded(context);
        } catch (IllegalStateException ex) {
            throw new FatalToolExecutionException("sandbox_error", ex.getMessage());
        }
    }

    private void emitFailure(
            OrchestratorServices services,
            FluxSink<AgentDelta> sink,
            ExecutionContext context,
            FailureOutcome failure
    ) {
        if (services == null || failure == null) {
            return;
        }
        AgentDelta.RunError error = failure.error();
        if (error != null && StringUtils.hasText(error.message())) {
            services.emit(sink, AgentDelta.content(error.message()));
        }
        services.emit(sink, AgentDelta.runError(error));
    }

    private FailureOutcome budgetExceededFailure(BudgetExceededException ex, ExecutionContext context) {
        return switch (ex.kind()) {
            case RUN_TIMEOUT -> {
                long timeoutMs = context == null || context.budget() == null ? 0L : Math.max(1L, context.budget().runTimeoutMs());
                long elapsedMs = context == null ? timeoutMs : Math.max(timeoutMs, context.elapsedMs());
                Map<String, Object> diagnostics = baseDiagnostics(context);
                diagnostics.put("elapsedMs", elapsedMs);
                diagnostics.put("timeoutMs", timeoutMs);
                diagnostics.put("limitName", "runTimeoutMs");
                diagnostics.put("limitValue", timeoutMs);
                yield new FailureOutcome(new AgentDelta.RunError(
                        "run_timeout",
                        "运行超时，本次执行已结束。已运行 " + elapsedMs + "ms，超过 runTimeoutMs=" + timeoutMs + "。",
                        "run",
                        "timeout",
                        diagnostics
                ));
            }
            case MODEL_CALLS -> {
                int limit = context == null || context.budget() == null ? 0 : context.budget().model().maxCalls();
                Map<String, Object> diagnostics = baseDiagnostics(context);
                diagnostics.put("limitName", "model.maxCalls");
                diagnostics.put("limitValue", limit);
                yield new FailureOutcome(new AgentDelta.RunError(
                        "model_calls_exceeded",
                        "模型调用次数已达上限，本次执行已结束。当前 modelCalls=" + (context == null ? 0 : context.modelCalls()) + "，上限为 " + limit + "。",
                        "model",
                        "budget",
                        diagnostics
                ));
            }
            case TOOL_CALLS -> {
                int limit = context == null || context.budget() == null ? 0 : context.budget().tool().maxCalls();
                Map<String, Object> diagnostics = baseDiagnostics(context);
                diagnostics.put("limitName", "tool.maxCalls");
                diagnostics.put("limitValue", limit);
                yield new FailureOutcome(new AgentDelta.RunError(
                        "tool_calls_exceeded",
                        "工具调用次数已达上限，本次执行已结束。当前 toolCalls=" + (context == null ? 0 : context.toolCalls()) + "，上限为 " + limit + "。",
                        "tool",
                        "budget",
                        diagnostics
                ));
            }
        };
    }

    private FailureOutcome frontendSubmitTimeoutFailure(FrontendSubmitTimeoutException ex, ExecutionContext context) {
        Map<String, Object> diagnostics = baseDiagnostics(context);
        if (ex != null) {
            if (ex.elapsedMs() > 0) {
                diagnostics.put("elapsedMs", ex.elapsedMs());
            }
            if (ex.timeoutMs() > 0) {
                diagnostics.put("timeoutMs", ex.timeoutMs());
            }
            if (StringUtils.hasText(ex.toolId())) {
                diagnostics.put("toolId", ex.toolId().trim());
            }
            if (StringUtils.hasText(ex.toolName())) {
                diagnostics.put("toolName", ex.toolName().trim());
            }
        }
        String message = ex == null || !StringUtils.hasText(ex.getMessage())
                ? FRONTEND_TIMEOUT_FALLBACK_MESSAGE
                : ex.getMessage().trim();
        if (message.contains("Frontend tool submit timeout")) {
            long elapsedMs = ex == null ? 0L : ex.elapsedMs();
            long timeoutMs = ex == null ? 0L : ex.timeoutMs();
            String toolName = ex == null ? null : normalize(ex.toolName());
            String toolId = ex == null ? null : normalize(ex.toolId());
            message = "前端工具等待用户提交超时，本次运行已结束。tool="
                    + (toolName == null ? "unknown" : toolName)
                    + ", toolId="
                    + (toolId == null ? "unknown" : toolId)
                    + ", elapsedMs="
                    + elapsedMs
                    + ", timeoutMs="
                    + timeoutMs;
        }
        return new FailureOutcome(new AgentDelta.RunError("frontend_submit_timeout", message, "frontend_submit", "timeout", diagnostics));
    }

    private FailureOutcome modelTimeoutFailure(ModelTimeoutException ex, ExecutionContext context) {
        Map<String, Object> diagnostics = baseDiagnostics(context);
        if (ex != null) {
            if (ex.elapsedMs() > 0) {
                diagnostics.put("elapsedMs", ex.elapsedMs());
            }
            if (ex.timeoutMs() > 0) {
                diagnostics.put("timeoutMs", ex.timeoutMs());
            }
            if (StringUtils.hasText(ex.stage())) {
                diagnostics.put("stage", ex.stage().trim());
            }
        }
        String stage = ex == null ? null : normalize(ex.stage());
        long elapsedMs = ex == null ? 0L : ex.elapsedMs();
        long timeoutMs = ex == null ? 0L : ex.timeoutMs();
        return new FailureOutcome(new AgentDelta.RunError(
                "model_timeout",
                "模型调用超时，本次执行已结束。stage="
                        + (stage == null ? "unknown" : stage)
                        + ", elapsedMs="
                        + elapsedMs
                        + ", timeoutMs="
                        + timeoutMs
                        + "。",
                "model",
                "timeout",
                diagnostics
        ));
    }

    private FailureOutcome fatalToolFailure(FatalToolExecutionException ex, ExecutionContext context) {
        Map<String, Object> diagnostics = baseDiagnostics(context);
        if (ex != null) {
            if (StringUtils.hasText(ex.toolId())) {
                diagnostics.put("toolId", ex.toolId().trim());
            }
            if (StringUtils.hasText(ex.toolName())) {
                diagnostics.put("toolName", ex.toolName().trim());
            }
        }
        String toolName = ex == null ? null : normalize(ex.toolName());
        String safeMessage;
        if (ex != null && isUserVisibleFatalCode(ex.code()) && StringUtils.hasText(ex.getMessage())) {
            safeMessage = ex.getMessage().trim();
        } else {
            safeMessage = "工具调用失败，本次运行已结束。tool=" + (toolName == null ? "unknown" : toolName) + "。";
        }
        return new FailureOutcome(new AgentDelta.RunError(
                "fatal_tool_error",
                safeMessage,
                "tool",
                "tool_error",
                diagnostics
        ));
    }

    private FailureOutcome internalFailure(ExecutionContext context) {
        Map<String, Object> diagnostics = baseDiagnostics(context);
        return new FailureOutcome(new AgentDelta.RunError(
                "internal_run_error",
                "运行异常，本次执行已结束。请稍后重试。",
                "internal",
                "internal",
                diagnostics
        ));
    }

    private Map<String, Object> baseDiagnostics(ExecutionContext context) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (context == null) {
            return diagnostics;
        }
        diagnostics.put("elapsedMs", context.elapsedMs());
        diagnostics.put("modelCalls", context.modelCalls());
        diagnostics.put("toolCalls", context.toolCalls());
        return diagnostics;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean isUserVisibleFatalCode(String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            return false;
        }
        return "tool_not_registered".equalsIgnoreCase(normalized)
                || "mcp_tool_missing".equalsIgnoreCase(normalized)
                || "mcp_server_not_found".equalsIgnoreCase(normalized)
                || "mcp_server_unavailable".equalsIgnoreCase(normalized)
                || "sandbox_error".equalsIgnoreCase(normalized);
    }

    private record FailureOutcome(AgentDelta.RunError error) {
    }
}
