package com.linlay.agentplatform.agent;

import com.linlay.agentplatform.agent.mode.OrchestratorServices;
import com.linlay.agentplatform.agent.runtime.BudgetExceededException;
import com.linlay.agentplatform.agent.runtime.ContainerHubSandboxService;
import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.FatalToolExecutionException;
import com.linlay.agentplatform.agent.runtime.FrontendSubmitTimeoutException;
import com.linlay.agentplatform.agent.runtime.RunInterruptedException;
import com.linlay.agentplatform.agent.runtime.RunLoopState;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.tool.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.FluxSink;

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
            services.emit(sink, AgentDelta.content(resolveFatalToolMessage(ex)));
            services.emit(sink, AgentDelta.finish("tool_error"));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (FrontendSubmitTimeoutException ex) {
            log.info("[agent:{}] frontend submit timeout: {}", agentId, ex.getMessage());
            if (StringUtils.hasText(ex.toolId()) && StringUtils.hasText(ex.resultText())) {
                services.emit(sink, AgentDelta.toolResult(ex.toolId(), ex.resultText()));
            }
            services.emit(sink, AgentDelta.content(resolveFrontendTimeoutMessage(ex)));
            services.emit(sink, AgentDelta.finish("timeout"));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (BudgetExceededException ex) {
            log.warn("[agent:{}] budget exceeded kind={}, message={}", agentId, ex.kind(), ex.getMessage());
            services.emit(sink, AgentDelta.content(resolveBudgetExceededMessage(ex)));
            services.emit(sink, AgentDelta.finish("budget_exceeded"));
            context.runControl().transitionState(RunLoopState.FAILED);
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (Exception ex) {
            log.warn("[agent:{}] orchestration failed", agentId, ex);
            services.emit(sink, AgentDelta.content("模型调用失败，请稍后重试。"));
            services.emit(sink, AgentDelta.finish("stop"));
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

    private String resolveFrontendTimeoutMessage(FrontendSubmitTimeoutException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return FRONTEND_TIMEOUT_FALLBACK_MESSAGE;
        }
        String raw = ex.getMessage().trim();
        if (raw.contains("Frontend tool submit timeout")) {
            return FRONTEND_TIMEOUT_FALLBACK_MESSAGE;
        }
        return raw;
    }

    private String resolveBudgetExceededMessage(BudgetExceededException ex) {
        return switch (ex.kind()) {
            case RUN_TIMEOUT -> "运行超时，本次执行已结束。请缩短问题范围或稍后重试。";
            case MODEL_CALLS -> "模型调用次数已达上限，本次执行已结束。";
            case TOOL_CALLS -> "工具调用次数已达上限，本次执行已结束。";
        };
    }

    private String resolveFatalToolMessage(FatalToolExecutionException ex) {
        if (ex == null || !StringUtils.hasText(ex.getMessage())) {
            return "工具调用失败，本次运行已结束。";
        }
        return ex.getMessage().trim();
    }
}
