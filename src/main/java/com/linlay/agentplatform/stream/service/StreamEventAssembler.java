package com.linlay.agentplatform.stream.service;

import com.linlay.agentplatform.stream.model.StreamEnvelope;
import com.linlay.agentplatform.stream.model.StreamEvent;
import com.linlay.agentplatform.stream.model.StreamInput;
import com.linlay.agentplatform.stream.model.StreamRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class StreamEventAssembler {

    public static final String INTERNAL_PARAM_EMIT_CHAT_START = "__emit_chat_start";

    public EventStreamState begin(StreamRequest request) {
        return new EventStreamState(Objects.requireNonNull(request, "request must not be null"));
    }

    public static final class EventStreamState {

        private static final Logger log = LoggerFactory.getLogger(EventStreamState.class);

        private final AtomicLong seq = new AtomicLong(0);
        private final List<StreamEvent> bootstrapEvents = new ArrayList<>();

        private final StreamRequest request;
        private final String chatId;
        private final String chatName;
        private final String runId;
        private final boolean emitChatStart;
        private final StreamEventStateData stateData;
        private final StreamEventDispatcher dispatcher;

        private EventStreamState(StreamRequest request) {
            this.request = request;
            this.chatId = StreamEventSupport.resolveChatId(request);
            this.chatName = request instanceof StreamRequest.Query q
                    ? StreamEventSupport.defaultIfBlank(q.chatName(), "default")
                    : null;
            this.runId = StreamEventSupport.resolveRunId(request);
            this.emitChatStart = request instanceof StreamRequest.Query query
                    ? StreamEventSupport.resolveEmitChatStart(query.params())
                    : false;
            this.stateData = new StreamEventStateData();
            this.dispatcher = new StreamEventDispatcher(request, chatId, runId, stateData, this::next);
            bootstrap();
        }

        public List<StreamEvent> bootstrapEvents() {
            return List.copyOf(bootstrapEvents);
        }

        public List<StreamEvent> consume(StreamEnvelope envelope) {
            List<StreamEvent> events = new ArrayList<>();
            if (envelope == null || envelope.input() == null) {
                return events;
            }
            if (stateData.terminated()) {
                log.warn("consume() called after stream already terminated; input ignored: {}", envelope.input());
                return events;
            }

            try {
                dispatcher.dispatch(events, envelope.input());
            } catch (RuntimeException ex) {
                if (!dispatcher.hasRunContext()) {
                    throw ex;
                }
                events.addAll(fail(ex));
            }
            return events;
        }

        public List<StreamEvent> consume(StreamInput input) {
            return consume(StreamEnvelope.of(input));
        }

        public List<StreamEvent> complete() {
            if (stateData.terminated()) {
                return List.of();
            }
            List<StreamEvent> events = new ArrayList<>();
            if (!dispatcher.hasRunContext()) {
                stateData.setTerminated(true);
                return events;
            }
            dispatcher.complete(events, null);
            return events;
        }

        public List<StreamEvent> fail(Throwable ex) {
            if (stateData.terminated()) {
                return List.of();
            }
            if (!dispatcher.hasRunContext()) {
                stateData.setTerminated(true);
                return List.of();
            }
            List<StreamEvent> events = new ArrayList<>();
            dispatcher.fail(events, ex);
            return events;
        }

        private void bootstrap() {
            if (request instanceof StreamRequest.Query query) {
                bootstrapQuery(query);
                return;
            }
            if (request instanceof StreamRequest.Upload upload) {
                bootstrapUpload(upload);
                return;
            }
            bootstrapSubmit((StreamRequest.Submit) request);
        }

        private void bootstrapQuery(StreamRequest.Query query) {
            Map<String, Object> visibleParams = StreamEventSupport.filterVisibleQueryParams(query.params());
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("requestId", query.requestId());
            requestPayload.put("chatId", query.chatId());
            requestPayload.put("role", query.role());
            requestPayload.put("message", query.message());
            StreamEventSupport.putIfNonNull(requestPayload, "agentKey", query.agentKey());
            StreamEventSupport.putIfNonNull(requestPayload, "teamId", query.teamId());
            StreamEventSupport.putIfNonNull(requestPayload, "references", query.references());
            StreamEventSupport.putIfNonNull(requestPayload, "params", visibleParams);
            StreamEventSupport.putIfNonNull(requestPayload, "scene", query.scene());
            StreamEventSupport.putIfNonNull(requestPayload, "stream", query.stream());
            StreamEventSupport.putIfNonNull(requestPayload, "hidden", query.hidden());
            bootstrapEvents.add(next("request.query", requestPayload));

            if (emitChatStart) {
                Map<String, Object> chatPayload = new LinkedHashMap<>();
                chatPayload.put("chatId", query.chatId());
                StreamEventSupport.putIfNonNull(chatPayload, "chatName", chatName);
                bootstrapEvents.add(next("chat.start", chatPayload));
            }

            Map<String, Object> runPayload = new LinkedHashMap<>();
            String runAgentKey = StreamEventSupport.requireRunStartAgentKey(query.agentKey(), query.chatId(), runId);
            runPayload.put("runId", runId);
            runPayload.put("chatId", query.chatId());
            runPayload.put("agentKey", runAgentKey);
            bootstrapEvents.add(next("run.start", runPayload));
        }

        private void bootstrapUpload(StreamRequest.Upload upload) {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("requestId", upload.requestId());
            StreamEventSupport.putIfNonNull(requestPayload, "chatId", upload.chatId());
            Map<String, Object> uploadPayload = new LinkedHashMap<>();
            uploadPayload.put("type", upload.uploadType());
            uploadPayload.put("name", upload.uploadName());
            uploadPayload.put("sizeBytes", upload.sizeBytes());
            uploadPayload.put("mimeType", upload.mimeType());
            StreamEventSupport.putIfNonNull(uploadPayload, "sha256", upload.sha256());
            requestPayload.put("upload", uploadPayload);
            bootstrapEvents.add(next("request.upload", requestPayload));
        }

        private void bootstrapSubmit(StreamRequest.Submit submit) {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("requestId", submit.requestId());
            requestPayload.put("chatId", submit.chatId());
            requestPayload.put("runId", submit.runId());
            requestPayload.put("toolId", submit.toolId());
            requestPayload.put("payload", submit.payload());
            StreamEventSupport.putIfNonNull(requestPayload, "viewId", submit.viewId());
            bootstrapEvents.add(next("request.submit", requestPayload));
        }

        private StreamEvent next(String type, Map<String, Object> payload) {
            return new StreamEvent(seq.incrementAndGet(), type, Instant.now().toEpochMilli(), payload);
        }
    }
}
