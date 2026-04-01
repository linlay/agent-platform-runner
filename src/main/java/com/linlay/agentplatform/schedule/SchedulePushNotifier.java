package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class SchedulePushNotifier {

    private static final Logger log = LoggerFactory.getLogger(SchedulePushNotifier.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SchedulePushNotifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void push(String pushUrl, String targetId, String markdown) {
        if (!StringUtils.hasText(pushUrl)) {
            return;
        }
        if (!StringUtils.hasText(markdown)) {
            log.debug("Skip push: empty markdown for targetId={}", targetId);
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "targetId", targetId,
                    "markdown", markdown
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pushUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Push sent targetId={}, markdownLen={}", targetId, markdown.length());
            } else {
                log.warn("Push failed targetId={}, status={}, body={}", targetId, response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("Push error targetId={}", targetId, ex);
        }
    }
}
