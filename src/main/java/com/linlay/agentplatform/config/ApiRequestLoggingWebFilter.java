package com.linlay.agentplatform.config;

import com.linlay.agentplatform.config.properties.LoggingAgentProperties;
import com.linlay.agentplatform.util.LoggingSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ApiRequestLoggingWebFilter implements WebFilter {

    public static final String ATTR_REQUEST_ID = "API_REQUEST_LOG_REQUEST_ID";
    public static final String ATTR_RUN_ID = "API_REQUEST_LOG_RUN_ID";
    public static final String ATTR_BODY_SUMMARY = "API_REQUEST_LOG_BODY_SUMMARY";
    public static final String ATTR_AUTH_REJECT_REASON = "API_REQUEST_LOG_AUTH_REJECT_REASON";

    private static final Logger log = LoggerFactory.getLogger("api.req");

    private final LoggingAgentProperties loggingAgentProperties;

    public ApiRequestLoggingWebFilter(LoggingAgentProperties loggingAgentProperties) {
        this.loggingAgentProperties = loggingAgentProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!loggingAgentProperties.getRequest().isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (!StringUtils.hasText(path) || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        long startNanos = System.nanoTime();
        return chain.filter(exchange)
                .doOnSuccess(ignored -> logRequestSummary(exchange, startNanos, null))
                .doOnError(ex -> logRequestSummary(exchange, startNanos, ex));
    }

    private void logRequestSummary(ServerWebExchange exchange, long startNanos, Throwable ex) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod() == null ? "UNKNOWN" : request.getMethod().name();
        String path = request.getPath().value();
        String requestId = resolveRequestId(exchange);
        String runId = resolveRunId(exchange);
        String authRejectReason = resolveAuthRejectReason(exchange);
        int status = exchange.getResponse().getStatusCode() == null
                ? (ex == null ? 200 : 500)
                : exchange.getResponse().getStatusCode().value();
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

        String query = "";
        if (loggingAgentProperties.getRequest().isIncludeQuery()) {
            query = sanitizeQuery(request.getQueryParams());
        }

        Map<String, Object> bodySummary = Map.of();
        if (loggingAgentProperties.getRequest().isIncludeBody()) {
            bodySummary = resolveBodySummary(exchange);
        }

        String target = query.isBlank() ? path : path + "?" + query;
        if (!bodySummary.isEmpty()) {
            if (StringUtils.hasText(authRejectReason)) {
                log.info(
                        "{} {} -> {} {}ms rid={} run={} auth={} body={}",
                        method,
                        target,
                        status,
                        latencyMs,
                        requestId,
                        runId,
                        authRejectReason,
                        bodySummary
                );
                return;
            }
            log.info(
                    "{} {} -> {} {}ms rid={} run={} body={}",
                    method,
                    target,
                    status,
                    latencyMs,
                    requestId,
                    runId,
                    bodySummary
            );
            return;
        }
        if (StringUtils.hasText(authRejectReason)) {
            log.info(
                    "{} {} -> {} {}ms rid={} run={} auth={}",
                    method,
                    target,
                    status,
                    latencyMs,
                    requestId,
                    runId,
                    authRejectReason
            );
            return;
        }
        log.info(
                "{} {} -> {} {}ms rid={} run={}",
                method,
                target,
                status,
                latencyMs,
                requestId,
                runId
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveBodySummary(ServerWebExchange exchange) {
        Object raw = exchange.getAttribute(ATTR_BODY_SUMMARY);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            typed.put(
                    entry.getKey() == null ? "null" : String.valueOf(entry.getKey()),
                    entry.getValue()
            );
        }
        return LoggingSanitizer.sanitizeMap(typed);
    }

    private String sanitizeQuery(MultiValueMap<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        MultiValueMap<String, String> copy = new LinkedMultiValueMap<>(queryParams);
        return copy.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    String value = entry.getValue() == null
                            ? ""
                            : entry.getValue().stream()
                            .map(v -> LoggingSanitizer.maskIfSensitiveKey(key, v))
                            .collect(Collectors.joining(","));
                    return key + "=" + value;
                })
                .collect(Collectors.joining("&"));
    }

    private String resolveRequestId(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(ATTR_REQUEST_ID);
        if (attr != null && StringUtils.hasText(String.valueOf(attr))) {
            return String.valueOf(attr);
        }
        String fromQuery = exchange.getRequest().getQueryParams().getFirst("requestId");
        return StringUtils.hasText(fromQuery) ? LoggingSanitizer.sanitizeText(fromQuery) : "-";
    }

    private String resolveRunId(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(ATTR_RUN_ID);
        if (attr != null && StringUtils.hasText(String.valueOf(attr))) {
            return String.valueOf(attr);
        }
        String fromQuery = exchange.getRequest().getQueryParams().getFirst("runId");
        return StringUtils.hasText(fromQuery) ? LoggingSanitizer.sanitizeText(fromQuery) : "-";
    }

    private String resolveAuthRejectReason(ServerWebExchange exchange) {
        Object attr = exchange.getAttribute(ATTR_AUTH_REJECT_REASON);
        if (attr == null) {
            return "";
        }
        return LoggingSanitizer.sanitizeText(String.valueOf(attr));
    }
}
