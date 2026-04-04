package com.linlay.agentplatform.agent.mode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.agent.PlannedToolCall;
import com.linlay.agentplatform.agent.runtime.execution.ExecutionContext;
import com.linlay.agentplatform.agent.runtime.exception.RunInterruptedException;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.stream.model.LlmDelta;
import com.linlay.agentplatform.stream.model.ToolCallDelta;
import com.linlay.agentplatform.util.StringHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModelTurnAccumulator {

    private static final Logger log = LoggerFactory.getLogger(ModelTurnAccumulator.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ModelTurnAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OrchestratorServices.ModelTurn accumulate(
            Iterable<LlmDelta> deltas,
            ExecutionContext context,
            String stage,
            boolean emitReasoning,
            boolean emitContent,
            boolean emitToolCalls,
            FluxSink<AgentDelta> sink
    ) {
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        Map<String, ToolAccumulator> toolsById = new LinkedHashMap<>();
        ToolAccumulator latest = null;
        int seq = 0;
        int deltaSeq = 0;
        boolean toolCallObserved = false;
        String activeStreamedToolCallId = null;
        Map<String, Object> capturedUsage = null;

        for (LlmDelta delta : deltas) {
            if (delta == null) {
                continue;
            }
            deltaSeq++;

            boolean hasToolCalls = delta.toolCalls() != null && !delta.toolCalls().isEmpty();
            boolean allowTextEmission = !toolCallObserved;

            if (StringUtils.hasText(delta.reasoning())) {
                reasoning.append(delta.reasoning());
                if (emitReasoning && allowTextEmission) {
                    emit(sink, AgentDelta.reasoning(delta.reasoning(), context.activeTaskId()));
                }
            }

            if (StringUtils.hasText(delta.content())) {
                content.append(delta.content());
                if (emitContent && allowTextEmission) {
                    emit(sink, AgentDelta.content(delta.content(), context.activeTaskId()));
                }
            }

            if (hasToolCalls) {
                for (ToolCallDelta call : delta.toolCalls()) {
                    if (call == null) {
                        continue;
                    }
                    String callId = normalize(call.id());
                    if (!StringUtils.hasText(callId)) {
                        callId = latest == null ? "call_native_" + (++seq) : latest.callId;
                    }

                    ToolAccumulator acc = toolsById.computeIfAbsent(callId, ToolAccumulator::new);
                    if (StringUtils.hasText(call.name())) {
                        acc.toolName = call.name();
                    }
                    if (StringUtils.hasText(call.type())) {
                        acc.toolType = call.type();
                    }
                    if (StringUtils.hasText(call.arguments())) {
                        acc.arguments.append(call.arguments());
                    }
                    String emittedName = StringUtils.hasText(call.name()) ? call.name() : acc.toolName;
                    String argumentsDelta = call.arguments();
                    if (emitToolCalls
                            && StringUtils.hasText(activeStreamedToolCallId)
                            && !activeStreamedToolCallId.equals(callId)) {
                        emit(sink, AgentDelta.toolEnd(activeStreamedToolCallId));
                        activeStreamedToolCallId = null;
                    }
                    latest = acc;
                    if (isPlanGenerateStage(stage) && StringUtils.hasText(argumentsDelta)) {
                        log.info(
                                "[plan-delta] runId={}, stage={}, deltaSeq={}, toolCallId={}, toolName={}, argumentsDelta={}",
                                context.request().runId(),
                                stage,
                                deltaSeq,
                                callId,
                                emittedName,
                                argumentsDelta
                        );
                    }

                    if (!emitToolCalls || !StringUtils.hasText(call.arguments())) {
                        continue;
                    }
                    String emittedType = StringUtils.hasText(call.type())
                            ? call.type()
                            : (StringUtils.hasText(acc.toolType) ? acc.toolType : "function");
                    emit(sink, AgentDelta.toolCalls(
                            List.of(new ToolCallDelta(callId, emittedType, emittedName, call.arguments())),
                            context.activeTaskId()
                    ));
                    activeStreamedToolCallId = callId;
                }
                toolCallObserved = true;
            }

            if (delta.usage() != null && !delta.usage().isEmpty()) {
                capturedUsage = delta.usage();
                emit(sink, AgentDelta.usage(capturedUsage));
            }
            failIfInterrupted(context);
        }

        failIfInterrupted(context);
        if (emitToolCalls && StringUtils.hasText(activeStreamedToolCallId)) {
            emit(sink, AgentDelta.toolEnd(activeStreamedToolCallId));
        }

        List<PlannedToolCall> plannedToolCalls = new ArrayList<>();
        for (ToolAccumulator acc : toolsById.values()) {
            String toolName = normalize(acc.toolName).toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(toolName)) {
                continue;
            }
            Map<String, Object> args = parseMap(acc.arguments.toString());
            plannedToolCalls.add(new PlannedToolCall(toolName, args, acc.callId));
        }

        return new OrchestratorServices.ModelTurn(content.toString(), reasoning.toString(), plannedToolCalls, capturedUsage);
    }

    private void emit(FluxSink<AgentDelta> sink, AgentDelta delta) {
        if (sink == null || delta == null || sink.isCancelled()) {
            return;
        }
        sink.next(delta);
    }

    private Map<String, Object> parseMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject()) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> mapped = objectMapper.convertValue(node, MAP_TYPE);
            return mapped == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mapped);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String normalize(String value) {
        return StringHelpers.trimToEmpty(value);
    }

    private boolean isPlanGenerateStage(String stage) {
        return "agent-plan-generate".equals(stage);
    }

    private void failIfInterrupted(ExecutionContext context) {
        if (context != null && context.isInterrupted()) {
            throw new RunInterruptedException();
        }
    }

    private static final class ToolAccumulator {
        private final String callId;
        private String toolName;
        private String toolType;
        private final StringBuilder arguments = new StringBuilder();

        private ToolAccumulator(String callId) {
            this.callId = callId;
        }
    }
}
