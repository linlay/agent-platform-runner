package com.linlay.agentplatform.stream.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.config.H2aProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RenderQueue {

    private static final String SSE_EVENT_MESSAGE = "message";

    private final ObjectMapper objectMapper;
    private final H2aProperties properties;
    private final Scheduler sharedScheduler = Schedulers.newSingle("h2a-render-queue");

    public RenderQueue(ObjectMapper objectMapper, H2aProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PreDestroy
    public void dispose() {
        sharedScheduler.dispose();
    }

    public Flux<ServerSentEvent<String>> buffer(Flux<ServerSentEvent<String>> source) {
        H2aProperties.Render render = properties.getRender();
        if (render.getFlushIntervalMs() <= 0L
                && render.getMaxBufferedChars() <= 0
                && render.getMaxBufferedEvents() <= 0) {
            return source;
        }

        return Flux.create(sink -> {
            Object monitor = new Object();
            List<ServerSentEvent<String>> pending = new ArrayList<>();
            int[] bufferedChars = {0};
            Scheduler.Worker worker = sharedScheduler.createWorker();
            AtomicBoolean timerScheduled = new AtomicBoolean(false);

            Runnable flush = () -> {
                List<ServerSentEvent<String>> drained;
                synchronized (monitor) {
                    if (pending.isEmpty()) {
                        timerScheduled.set(false);
                        return;
                    }
                    drained = new ArrayList<>(pending);
                    pending.clear();
                    bufferedChars[0] = 0;
                    timerScheduled.set(false);
                }
                drained.forEach(sink::next);
            };

            Disposable upstream = source.subscribe(
                    event -> {
                        if (shouldPassThroughHeartbeat(render, event)) {
                            flush.run();
                            sink.next(event);
                            return;
                        }
                        boolean shouldFlush;
                        synchronized (monitor) {
                            pending.add(event);
                            bufferedChars[0] += estimateChars(event);
                            shouldFlush = shouldFlush(render, pending.size(), bufferedChars[0], event);
                            if (!shouldFlush && render.getFlushIntervalMs() > 0L && timerScheduled.compareAndSet(false, true)) {
                                worker.schedule(flush, render.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                            }
                        }
                        if (shouldFlush) {
                            flush.run();
                        }
                    },
                    error -> {
                        flush.run();
                        sink.error(error);
                    },
                    () -> {
                        flush.run();
                        sink.complete();
                    }
            );

            sink.onDispose(() -> {
                upstream.dispose();
                worker.dispose();
            });
        });
    }

    private boolean shouldPassThroughHeartbeat(H2aProperties.Render render, ServerSentEvent<String> event) {
        return render.isHeartbeatPassThrough() && event != null && StringUtils.hasText(event.comment());
    }

    private boolean shouldFlush(H2aProperties.Render render, int bufferedEvents, int bufferedChars, ServerSentEvent<String> latest) {
        if (isTerminalEvent(latest)) {
            return true;
        }
        if (render.getMaxBufferedEvents() > 0 && bufferedEvents >= render.getMaxBufferedEvents()) {
            return true;
        }
        return render.getMaxBufferedChars() > 0 && bufferedChars >= render.getMaxBufferedChars();
    }

    private int estimateChars(ServerSentEvent<String> event) {
        if (event == null || event.data() == null) {
            return 0;
        }
        return event.data().length();
    }

    private boolean isTerminalEvent(ServerSentEvent<String> event) {
        if (event == null || !StringUtils.hasText(event.data()) || !SSE_EVENT_MESSAGE.equals(event.event())) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(event.data());
            String type = root.path("type").asText("");
            return "run.complete".equals(type) || "run.cancel".equals(type) || "run.error".equals(type);
        } catch (Exception ignored) {
            return false;
        }
    }
}
