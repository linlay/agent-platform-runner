package com.linlay.agentplatform.engine.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.util.LoggingSanitizer;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

/**
 * Extracted tool/action invocation logging helpers from ToolExecutionService.
 */
final class ToolInvocationLogger {

    private final Logger log;
    private final LoggingAgentProperties loggingAgentProperties;

    ToolInvocationLogger(Logger log, LoggingAgentProperties loggingAgentProperties) {
        this.log = log;
        this.loggingAgentProperties = loggingAgentProperties;
    }

    void logInvocationStart(String runId, String taskId, String callId, String toolName, String toolType, String argsJson) {
        LoggingAgentProperties.Tool config = resolveInvocationLoggingConfig(toolType);
        if (config == null || !config.isEnabled()) {
            return;
        }
        String channel = isActionType(toolType) ? "action" : "tool";
        if (config.isIncludeArgs()) {
            log.info(
                    "agent.{}.start runId={}, taskId={}, id={}, name={}, type={}, args={}",
                    channel,
                    runId,
                    taskId,
                    callId,
                    toolName,
                    toolType,
                    LoggingSanitizer.sanitizeText(argsJson)
            );
            return;
        }
        log.info(
                "agent.{}.start runId={}, taskId={}, id={}, name={}, type={}",
                channel,
                runId,
                taskId,
                callId,
                toolName,
                toolType
        );
    }

    void logInvocationEnd(String runId, String taskId, String callId, String toolName, String toolType, JsonNode resultNode, long elapsedMs) {
        LoggingAgentProperties.Tool config = resolveInvocationLoggingConfig(toolType);
        if (config == null || !config.isEnabled()) {
            return;
        }
        String channel = isActionType(toolType) ? "action" : "tool";
        boolean failed = resultNode != null
                && resultNode.isObject()
                && resultNode.has("ok")
                && !resultNode.path("ok").asBoolean(true);
        if (config.isIncludeResult()) {
            String resultText = resultNode == null ? "" : LoggingSanitizer.sanitizeText(resultNode.toString());
            if (failed) {
                log.warn(
                        "agent.{}.end runId={}, taskId={}, id={}, name={}, elapsedMs={}, ok=false, result={}",
                        channel,
                        runId,
                        taskId,
                        callId,
                        toolName,
                        elapsedMs,
                        resultText
                );
            } else {
                log.info(
                        "agent.{}.end runId={}, taskId={}, id={}, name={}, elapsedMs={}, result={}",
                        channel,
                        runId,
                        taskId,
                        callId,
                        toolName,
                        elapsedMs,
                        resultText
                );
            }
            return;
        }
        if (failed) {
            log.warn(
                    "agent.{}.end runId={}, taskId={}, id={}, name={}, elapsedMs={}, ok=false",
                    channel,
                    runId,
                    taskId,
                    callId,
                    toolName,
                    elapsedMs
            );
        } else {
            log.info(
                    "agent.{}.end runId={}, taskId={}, id={}, name={}, elapsedMs={}",
                    channel,
                    runId,
                    taskId,
                    callId,
                    toolName,
                    elapsedMs
            );
        }
    }

    void logToolExecutionFailure(String toolName, int attempt, int retries, Exception ex) {
        LoggingAgentProperties.Tool toolConfig = loggingAgentProperties == null ? null : loggingAgentProperties.getTool();
        if (toolConfig == null || !toolConfig.isEnabled()) {
            return;
        }
        log.warn(
                "agent.tool.failure toolName={}, attempt={}, retries={}, reason={}",
                toolName,
                attempt,
                retries,
                LoggingSanitizer.sanitizeText(ex == null ? "" : ex.getMessage())
        );
    }

    private LoggingAgentProperties.Tool resolveInvocationLoggingConfig(String toolType) {
        if (loggingAgentProperties == null) {
            return null;
        }
        return isActionType(toolType) ? loggingAgentProperties.getAction() : loggingAgentProperties.getTool();
    }

    private boolean isActionType(String toolType) {
        return StringUtils.hasText(toolType) && "action".equalsIgnoreCase(toolType);
    }
}
