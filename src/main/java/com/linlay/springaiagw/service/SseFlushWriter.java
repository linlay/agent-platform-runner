package com.linlay.springaiagw.service;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class SseFlushWriter {

    public Mono<Void> write(ServerHttpResponse response, Flux<ServerSentEvent<String>> events) {
        response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
        response.getHeaders().set("X-Accel-Buffering", "no");
        response.getHeaders().set("Cache-Control", "no-cache, no-transform");
        response.getHeaders().set("Connection", "keep-alive");

        return response.writeAndFlushWith(
                events.map(this::encode)
                        .map(response.bufferFactory()::wrap)
                        .map(Mono::just)
        );
    }

    private byte[] encode(ServerSentEvent<String> event) {
        StringBuilder builder = new StringBuilder();

        if (StringUtils.hasText(event.id())) {
            builder.append("id:").append(event.id()).append('\n');
        }
        if (StringUtils.hasText(event.event())) {
            builder.append("event:").append(event.event()).append('\n');
        }
        if (event.retry() != null) {
            builder.append("retry:").append(event.retry().toMillis()).append('\n');
        }
        if (event.comment() != null) {
            String[] commentLines = event.comment().split("\\R", -1);
            for (String line : commentLines) {
                builder.append(':').append(line).append('\n');
            }
        }

        String data = event.data();
        if (data != null) {
            String[] dataLines = data.split("\\R", -1);
            for (String line : dataLines) {
                builder.append("data:").append(line).append('\n');
            }
        }
        builder.append('\n');

        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
